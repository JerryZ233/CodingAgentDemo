package com.demo.cli;

import com.demo.session.*;
import com.demo.tools.process.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.jline.terminal.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TerminalApprovalInputTest {
    @TempDir Path root;
    static final class Run implements RunHandle {
        final CompletableFuture<SessionSnapshot> finished=new CompletableFuture<>();
        final AtomicReference<ApprovalCoordinator.PendingApproval> pending=new AtomicReference<>();
        final BlockingQueue<ApprovalCoordinator.Decision> decisions=new LinkedBlockingQueue<>();
        volatile int revoked;
        volatile boolean keepPending;
        public String sessionId(){return "internal-session";}public String runId(){return "internal-run";}
        public State state(){return State.WAITING_APPROVAL;}
        public CompletionStage<SessionSnapshot> completion(){return finished;}
        public List<SessionEvent> eventsAfter(long sequence){return List.of();}
        public CancelResult cancel(){pending.set(null);finished.complete(null);return CancelResult.REQUESTED;}
        public Optional<ApprovalCoordinator.PendingApproval> pendingApproval(){return Optional.ofNullable(pending.get());}
        public void revokeApprovals(){revoked++;}
        public boolean submitApproval(long generation,ApprovalCoordinator.Decision choice){
            var p=pending.get();if(p==null||p.generation()!=generation)return false;
            if(!keepPending)pending.set(null);decisions.add(choice);return true;
        }
    }
    @Test void alreadyAnsweredRequestIsNotDisplayedAgainWhileBackendClearsIt()throws Exception {
        var input=new PipedInputStream();var writer=new PipedOutputStream(input);var out=new ByteArrayOutputStream();
        var pool=Executors.newSingleThreadExecutor();var run=new Run();run.keepPending=true;
        var plan=new CommandPlanner(root,ShellPolicyConfig.defaults()).prepare("{\"mode\":\"script\",\"script\":\"echo hello\"}");
        run.pending.set(new ApprovalCoordinator.PendingApproval(101,plan));
        try(var terminal=TerminalBuilder.builder().system(false).type("xterm").encoding(StandardCharsets.UTF_8).streams(input,out).size(new Size(80,24)).build()) {
            var reading=pool.submit(()->new TerminalApprovalInput(terminal,new Object()).read(run,false));awaitText(out,"请选择 1 / 2 / 3：");
            writer.write("1\n".getBytes(StandardCharsets.UTF_8));writer.flush();
            assertEquals(ApprovalCoordinator.Decision.ONCE,run.decisions.poll(3,TimeUnit.SECONDS));
            assertNull(run.decisions.poll(150,TimeUnit.MILLISECONDS));
            assertEquals(1,out.toString(StandardCharsets.UTF_8).split("需要执行命令",-1).length-1);
            run.finished.complete(null);assertEquals(TerminalIO.Kind.WAKE,reading.get(3,TimeUnit.SECONDS).kind());
        }finally{run.cancel();writer.close();pool.shutdownNow();}
    }
    @Test void statusRevokeAndCancelRemainAvailableDuringApproval()throws Exception {
        var input=new PipedInputStream();var writer=new PipedOutputStream(input);var out=new ByteArrayOutputStream();
        var pool=Executors.newSingleThreadExecutor();var run=new Run();
        var plan=new CommandPlanner(root,ShellPolicyConfig.defaults()).prepare("{\"mode\":\"script\",\"script\":\"echo never\"}");
        run.pending.set(new ApprovalCoordinator.PendingApproval(100,plan));
        try(var terminal=TerminalBuilder.builder().system(false).type("xterm").encoding(StandardCharsets.UTF_8).streams(input,out).size(new Size(80,24)).build()) {
            var modal=new TerminalApprovalInput(terminal,new Object());var reading=pool.submit(()->modal.read(run,false));
            awaitText(out,"请选择 1 / 2 / 3：");
            writer.write("/status\n".getBytes(StandardCharsets.UTF_8));writer.flush();awaitText(out,"当前命令尚未启动");assertTrue(run.decisions.isEmpty());
            writer.write("/revoke\n".getBytes(StandardCharsets.UTF_8));writer.flush();awaitText(out,"已撤销本轮可复用授权");assertEquals(1,run.revoked);
            writer.write("/cancel\n".getBytes(StandardCharsets.UTF_8));writer.flush();
            assertEquals(TerminalIO.Kind.INTERRUPT,reading.get(3,TimeUnit.SECONDS).kind());assertTrue(run.decisions.isEmpty());assertTrue(run.pendingApproval().isEmpty());
        }finally{run.cancel();writer.close();pool.shutdownNow();}
    }
    void awaitText(ByteArrayOutputStream out,String text)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!out.toString(StandardCharsets.UTF_8).contains(text) && System.nanoTime()<deadline)Thread.sleep(5);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains(text),out.toString(StandardCharsets.UTF_8));
    }
    @Test void bufferedInputAndBracketedPasteNeverApproveAndEditedTaskIsRestored()throws Exception {
        var input=new PipedInputStream();var writer=new PipedOutputStream(input);var out=new ByteArrayOutputStream();
        var pool=Executors.newSingleThreadExecutor();var run=new Run();
        var plan=new CommandPlanner(root,ShellPolicyConfig.defaults()).prepare("{\"mode\":\"script\",\"script\":\"echo hello\"}");
        try(var nativeTerminal=TerminalBuilder.builder().system(false).type("xterm").encoding(StandardCharsets.UTF_8).streams(input,out).build()) {
            var modal=new TerminalApprovalInput(nativeTerminal,new Object());
            var reading=pool.submit(()->modal.read(run,false));awaitText(out,"agent > ");
            writer.write("draft".getBytes(StandardCharsets.UTF_8));writer.flush();awaitText(out,"agent > draft");
            run.pending.set(new ApprovalCoordinator.PendingApproval(1,plan));
            writer.write("2\n".getBytes(StandardCharsets.UTF_8));writer.flush();awaitText(out,"请选择 1 / 2 / 3：");
            assertNull(run.decisions.poll(100,TimeUnit.MILLISECONDS));
            writer.write("\033[200~1\n2\n\033[201~\n".getBytes(StandardCharsets.UTF_8));writer.flush();
            awaitText(out,"粘贴内容不用于批准");assertTrue(run.decisions.isEmpty());
            writer.write("2\n".getBytes(StandardCharsets.UTF_8));writer.flush();
            assertEquals(ApprovalCoordinator.Decision.THIS_TURN,run.decisions.poll(3,TimeUnit.SECONDS));
            run.finished.complete(null);assertEquals(TerminalIO.Kind.WAKE,reading.get(3,TimeUnit.SECONDS).kind());
            assertEquals("draft",modal.takeDraft());
            assertFalse(out.toString(StandardCharsets.UTF_8).contains("internal-run"));
        }finally{run.cancel();writer.close();pool.shutdownNow();}
    }
}
