package com.demo.eval;

import com.demo.config.Config;
import com.demo.config.ConfigLoader;
import com.demo.llm.LLMClient;
import com.demo.llm.impl.OpenAICompatibleLLMClient;
import com.demo.logging.LogData;
import com.demo.session.*;
import com.demo.session.storage.JsonSessionRepository;
import com.demo.tools.process.ApprovalCoordinator;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Container-only, one-turn entry point. Completion is not a correctness verdict. */
public final class EvalMain {
    private EvalMain() { }

    public static void main(String[] args) { System.exit(run(args)); }

    static int run(String[] args) {
        EvalMetrics metrics = null;
        long started = System.nanoTime();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema_version", 1);
        result.put("started_at", Instant.now().toString());
        int code = 1;
        try {
            Options options = Options.parse(args);
            metrics = new EvalMetrics(options.output());
            result.put("workspace", options.workspace().toString());
            // This entry point grants execution plans automatically. Never enable it in the ordinary CLI.
            if (!"1".equals(System.getenv("CODING_AGENT_EVAL_CONTAINER")) || !Files.exists(Path.of("/.dockerenv"))) {
                throw new IllegalStateException("Evaluation entry point requires the dedicated Docker environment");
            }
            if (!"explicit".equals(System.getenv("CODING_AGENT_EVAL_MODEL_CALLS"))) {
                throw new IllegalStateException("Real-model evaluation requires explicit per-invocation consent");
            }
            Config config = ConfigLoader.load(Path.of(System.getenv().getOrDefault("EVAL_CONFIG", "/opt/eval/config.yaml")));
            LogData.registerSecret(config.getApiKey());
            if (config.getApiKey().isBlank()) throw new IllegalArgumentException("LLM_API_KEY is required; Dummy mode is disabled");
            result.put("model", config.getModel());
            result.put("provider", config.getProvider());
            result.put("max_iterations", config.getMaxIterations());
            result.put("timeout_seconds", options.timeoutSeconds());
            var client = new OpenAICompatibleLLMClient(config, metrics::record);
            code = execute(config, client, options, metrics, result);
        } catch (Exception failure) {
            result.put("agent_status", "FAILED");
            result.put("error", LogData.redact(failure.getClass().getSimpleName() + ": " + failure.getMessage()));
            System.err.println(result.get("error"));
        } finally {
            result.put("ended_at", Instant.now().toString());
            result.put("agent_elapsed_ms", (System.nanoTime() - started) / 1_000_000);
            result.put("exit_code", code);
            if (metrics != null) {
                result.putAll(metrics.snapshot());
                try { metrics.write("metrics.json", result); }
                catch (Exception failure) { System.err.println("Could not write evaluation metrics"); code = 1; }
            }
        }
        return code;
    }

    /** Owns the injected client. Kept separate for deterministic integration tests without paid calls. */
    static int execute(Config config, LLMClient client, Options options, EvalMetrics metrics,
                       Map<String, Object> result) throws Exception {
        try (var sessions = new DefaultSessionService(config, client,
                new JsonSessionRepository(options.output().resolve("sessions")), Clock.systemUTC(),
                DefaultSessionService.ClientOwnership.OWNED)) {
            String instruction = Files.readString(options.instruction(), StandardCharsets.UTF_8);
            var session = sessions.createSession(options.workspace());
            var handle = sessions.submitTurn(session.id(), instruction, RunOptions.interactive());
            var completion = handle.completion().toCompletableFuture();
            result.put("session_id", session.id());
            result.put("run_id", handle.runId());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(options.timeoutSeconds());
            boolean timedOut = false;
            long cursor = 0;
            while (true) {
                cursor = drain(handle, cursor, metrics);
                if (completion.isDone()) break;
                if (System.nanoTime() >= deadline) {
                    timedOut = true;
                    handle.cancel();
                    try { completion.get(15, TimeUnit.SECONDS); }
                    catch (TimeoutException timeout) { throw new TimeoutException("Cancellation cleanup exceeded 15 seconds"); }
                    break;
                }
                handle.pendingApproval().ifPresent(pending -> {
                    if (handle.submitApproval(pending.generation(), ApprovalCoordinator.Decision.ONCE)) {
                        metrics.append("events.jsonl", Map.of("type", "EVAL_APPROVAL", "generation", pending.generation(),
                                "decision", "ONCE", "mode", pending.plan().mode(), "cwd", pending.plan().cwd().toString()));
                    }
                });
                Thread.sleep(20);
            }
            drain(handle, cursor, metrics);
            var snapshot = completion.get();
            result.put("agent_status", timedOut ? "TIMED_OUT" : snapshot.lastRun().status().name());
            result.put("iterations", snapshot.lastRun().iterations());
            result.put("final_response", snapshot.lastRun().message());
            return timedOut ? 124 : snapshot.lastRun().status() == SessionRun.Status.COMPLETED ? 0 : 1;
        }
    }

    private static long drain(RunHandle handle, long cursor, EvalMetrics metrics) {
        for (var event : handle.eventsAfter(cursor)) {
            metrics.append("events.jsonl", Map.of("sequence", event.sequence(), "timestamp", event.timestamp().toString(),
                    "type", event.type().name(), "payload", event.payload()));
            cursor = event.sequence();
        }
        return cursor;
    }

    record Options(Path workspace, Path instruction, Path output, int timeoutSeconds) {
        static Options parse(String[] args) {
            Map<String, String> values = new HashMap<>();
            Set<String> allowed = Set.of("--workspace", "--instruction-file", "--output-dir", "--timeout-seconds");
            for (int i = 0; i < args.length; i += 2) {
                if (!allowed.contains(args[i]) || i + 1 >= args.length || values.putIfAbsent(args[i], args[i + 1]) != null) {
                    throw new IllegalArgumentException("Expected unique --workspace, --instruction-file, --output-dir and optional --timeout-seconds");
                }
            }
            for (String key : List.of("--workspace", "--instruction-file", "--output-dir")) {
                if (!values.containsKey(key)) throw new IllegalArgumentException("Missing " + key);
            }
            int seconds = Integer.parseInt(values.getOrDefault("--timeout-seconds", "600"));
            if (seconds < 1 || seconds > 86400) throw new IllegalArgumentException("Timeout must be 1..86400 seconds");
            return new Options(Path.of(values.get("--workspace")).toAbsolutePath().normalize(),
                    Path.of(values.get("--instruction-file")).toAbsolutePath().normalize(),
                    Path.of(values.get("--output-dir")).toAbsolutePath().normalize(), seconds);
        }
    }
}
