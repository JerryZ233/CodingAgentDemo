package com.demo.session;

import com.demo.config.Config;
import com.demo.llm.*;
import com.demo.model.*;
import com.demo.session.storage.JsonSessionRepository;
import com.demo.tools.process.ApprovalCoordinator;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ShellApprovalSessionTest {
    @TempDir Path root;
    DefaultSessionService service() {
        Config config=new Config(Config.DEFAULT_API_URL,"dummy","",2048,0,6,root.toString(),Set.of("run_shell"));
        AtomicInteger calls=new AtomicInteger();
        LLMClient model=(messages,tools)-> {
            int n=calls.incrementAndGet();
            if(n%3==0)return new LLMClient.LLMResponse("done",null);
            return new LLMClient.LLMResponse(null,List.of(new ToolCall("call"+n,"run_shell","{\"mode\":\"script\",\"script\":\"echo approved\"}")));
        };
        return new DefaultSessionService(com.demo.testing.TestConfigs.withoutMemory(config),model,new JsonSessionRepository(root.resolve("sessions")),Clock.systemUTC());
    }
    ApprovalCoordinator.PendingApproval pending(RunHandle run)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(run.pendingApproval().isEmpty() && !run.completion().toCompletableFuture().isDone() && System.nanoTime()<end)Thread.sleep(5);
        return run.pendingApproval().orElseThrow();
    }
    @Test void asyncGrantReusesWithinTurnButNeverAcrossTurns()throws Exception {
        try(var service=service()) {
            String id=service.createSession(root).id();
            var first=service.submitTurn(id,"run",RunOptions.interactive());var approval=pending(first);
            assertEquals(RunHandle.State.WAITING_APPROVAL,first.state());
            assertEquals(SessionRun.Status.RUNNING,service.getSession(id).lastRun().status());
            assertTrue(first.submitApproval(approval.generation(),ApprovalCoordinator.Decision.THIS_TURN));
            var finished=first.completion().toCompletableFuture().get(10,TimeUnit.SECONDS);
            assertEquals(SessionRun.Status.COMPLETED,finished.lastRun().status());
            assertEquals(1,first.eventsAfter(0).stream().filter(e->e.payload() instanceof SessionEvent.Approval a && a.kind().equals("requested")).count());
            for(Message m:finished.messages())if("tool".equals(m.getRole())) {
                assertFalse(m.getContent().contains(first.runId()));assertFalse(m.getContent().contains(id));
                var result=com.google.gson.JsonParser.parseString(m.getContent()).getAsJsonObject();
                Path artifact=Path.of(result.get("output_file").getAsString());
                var metadata=com.google.gson.JsonParser.parseString(Files.readString(artifact.resolveSibling(artifact.getFileName()+".metadata.json"))).getAsJsonObject();
                assertEquals(first.runId(),metadata.get("run_id").getAsString());
            }
            var second=service.submitTurn(id,"again",RunOptions.interactive());var next=pending(second);
            second.cancel();assertFalse(second.submitApproval(next.generation(),ApprovalCoordinator.Decision.ONCE));
            assertEquals(SessionRun.Status.CANCELLED,second.completion().toCompletableFuture().get(5,TimeUnit.SECONDS).lastRun().status());
        }
    }
    @Test void synchronousClientEndsWithToolErrorAndNoApprovalWait() {
        try(var service=service()) {
            var result=service.runTurn(service.createSession(root).id(),"run");
            assertEquals(SessionRun.Status.TOOL_ERROR,result.lastRun().status());
            assertEquals("APPROVAL_CHANNEL_UNAVAILABLE",result.lastRun().message());
        }
    }
}
