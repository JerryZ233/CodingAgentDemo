package com.demo.tools.process;

import com.demo.execution.*;
import com.demo.model.ToolResult;
import com.demo.tools.ToolExecutionContext;
import com.demo.tools.support.ToolLimits;
import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.*;

/** Executes host-approved, revalidated plans; not an OS sandbox. */
public final class ShellExecutor {
    private static final AtomicLong sequence = new AtomicLong();
    public ToolResult execute(ExecutionPlan plan, ToolExecutionContext context) {
        long start = System.nanoTime();
        ProcessBuilder builder = new ProcessBuilder(plan.commandLine()).directory(plan.cwd().toFile()).redirectErrorStream(true);
        builder.environment().clear(); builder.environment().putAll(plan.environment());
        boolean started = false;
        OutputPreview live=new OutputPreview(context.outputEvents());
        try {
            context.token().throwIfCancellationRequested();
            context.approvals().admitExecution(plan,context.token());
            if(!CommandPlanner.stillMatches(plan,context.token()))throw new ShellExecutionException("PLAN_INVALIDATED");
            AtomicReference<ManagedProcess> admitted = new AtomicReference<>();
            // Linearize launch admission, without holding cancellation/service locks during OS IO.
            context.token().runIfActive(() -> { });
            context.token().throwIfCancellationRequested();
            admitted.set(new ManagedProcess(builder, plan.outputLimit(), plan.encoding(),java.util.function.UnaryOperator.identity(),live::accept));
            started = true;
            try (ManagedProcess managed = admitted.get()) {
                ManagedProcess.Result result;
                try { result = managed.await(Duration.ofMillis(plan.timeoutMillis()), context.token()); }
                catch (OperationCancelledException cancelled) {
                    result = managed.stopAndCollect();
                    live.finish(result);
                    throw new ShellCancelledException(true, encode(plan, context, result, "cancelled", start).getOutput());
                }
                String status = result.outputFailed() ? "output_read_failed" : result.outputLimitExceeded() ? "output_limit_exceeded"
                        : result.timedOut() ? "timed_out" : "exited";
                live.finish(result);
                return encode(plan, context, result, status, start);
            }
        } catch (ShellCancelledException e) { checkCleanupFailure(e); throw e; }
          catch (OperationCancelledException e) { checkCleanupFailure(e); throw new ShellCancelledException(started); }
          catch (ResourceCleanupException e) { checkCleanupFailure(e); throw e; }
          catch (ShellExecutionException e) { checkCleanupFailure(e); throw new ShellExecutionException(e.getMessage(), started); }
          catch (InterruptedException e) { checkCleanupFailure(e); Thread.currentThread().interrupt(); throw new ShellCancelledException(started); }
          catch (Exception e) {
              checkCleanupFailure(e);
              boolean initialization = e instanceof ManagedProcess.InitializationFailure;
              return failure(initialization || !started ? "START_FAILED" : "EXECUTION_FAILED", started || initialization);
          }
    }
    private ToolResult encode(ExecutionPlan plan, ToolExecutionContext context, ManagedProcess.Result collected, String status, long start) {
        String retained = com.demo.logging.LogData.redact(collected.output());
        JsonObject result = new JsonObject();
        result.addProperty("status", status); result.addProperty("started", true);
        if(!status.equals("exited"))result.addProperty("error_code",status.equals("timed_out")?"COMMAND_TIMED_OUT":status.toUpperCase(java.util.Locale.ROOT));
        result.addProperty("exit_code", collected.exitCode()); result.addProperty("duration_ms", (System.nanoTime()-start)/1000000);
        result.addProperty("captured_bytes", retained.getBytes(StandardCharsets.UTF_8).length);
        result.addProperty("observed_bytes", collected.observedBytes()); result.addProperty("decoding_errors", collected.decodingError());
        boolean saved = false, saveFailed = false;
        if (context.outputDirectory() != null) {
            byte[] bytes = retained.getBytes(StandardCharsets.UTF_8);
            String name = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))+"-"+sequence.incrementAndGet()+".txt";
            byte[] metadata=null;
            if(context.runIdentity()!=null) {
                JsonObject association=new JsonObject();association.addProperty("version",1);
                association.addProperty("run_id",context.runIdentity());association.addProperty("output_file",name);
                metadata=association.toString().getBytes(StandardCharsets.UTF_8);
            }
            context.approvals().reserveArtifact(bytes.length+(metadata==null?0:metadata.length));
            try {
                Files.createDirectories(context.outputDirectory());
                Path artifact = context.outputDirectory().resolve(name);
                Files.write(artifact, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                if(metadata!=null)Files.write(context.outputDirectory().resolve(name+".metadata.json"),metadata,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
                result.addProperty("output_file", artifact.toString()); saved = true;
            } catch (java.io.IOException | SecurityException e) { saveFailed = true; result.addProperty("output_save_error", "OUTPUT_SAVE_FAILED"); }
        }
        result.addProperty("output_saved", saved);
        String preview = retained;
        result.addProperty("output", preview); result.addProperty("output_truncated", collected.truncated());
        while (result.toString().length() > ToolLimits.MESSAGE_CHARS) {
            int length = preview.length();
            if (length < 20) throw new ShellExecutionException("RESULT_BUDGET_EXCEEDED", true);
            int keep = length*3/8;
            preview = preview.substring(0,keep)+"\n[output truncated]\n"+preview.substring(length-keep);
            result.addProperty("output", preview); result.addProperty("output_truncated", true);
        }
        return new ToolResult("run_shell", collected.exitCode()==0 && status.equals("exited") && !saveFailed, result.toString());
    }
    public static ToolResult failure(String code, boolean started) {
        JsonObject result = new JsonObject(); result.addProperty("status", code); result.addProperty("started", started);
        result.addProperty("error_code",code);
        return ToolResult.error("run_shell", result.toString());
    }
    public static void checkCleanupFailure(Throwable failure) {
        checkCleanupFailure(failure,java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
    }
    private static void checkCleanupFailure(Throwable failure,java.util.Set<Throwable> visited) {
        if(failure==null || !visited.add(failure))return;
        for (Throwable suppressed : failure.getSuppressed()) {
            if (suppressed instanceof ResourceCleanupException resource) throw resource;
            checkCleanupFailure(suppressed,visited);
        }
        if(failure.getCause() instanceof ResourceCleanupException resource)throw resource;
        checkCleanupFailure(failure.getCause(),visited);
    }
}
