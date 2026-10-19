package com.demo.cli;

import com.demo.config.Config;
import com.demo.llm.LLMClient;
import com.demo.model.ToolCall;
import com.demo.session.*;
import com.demo.session.storage.JsonSessionRepository;
import com.demo.tools.process.ApprovalCoordinator;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ShellApprovalCliTest {
    @TempDir Path root;
    static final class Terminal implements TerminalIO {
        final List<String> output=new java.util.concurrent.CopyOnWriteArrayList<>();
        final boolean approvals;
        int inputs, requests;
        Terminal(boolean approvals){this.approvals=approvals;}
        public boolean supportsApprovals(){return approvals;}
        public Input readLine(String prompt){return Input.line(inputs++==0?"run":"/exit");}
        public Input readRunInput(RunHandle run,boolean singleTask) {
            run.pendingApproval().ifPresent(p->{
                output.add(TerminalApprovalInput.approvalText(p.plan()));requests++;
                run.submitApproval(p.generation(),ApprovalCoordinator.Decision.THIS_TURN);
            });
            try{Thread.sleep(10);}catch(InterruptedException e){Thread.currentThread().interrupt();run.cancel();}
            return new Input(Kind.WAKE,null);
        }
        public void print(String text){output.add(text);}public void error(String text){output.add(text);}
        public void printAbove(String text){output.add(text);}public void close(){}
    }
    int run(Terminal terminal,boolean single) {
        Config config=new Config(Config.DEFAULT_API_URL,"mock","",2048,0,6,root.toString(),Set.of("run_shell"));
        AtomicInteger calls=new AtomicInteger();
        LLMClient model=(messages,tools)->{
            int n=calls.incrementAndGet();
            return n<=2?new LLMClient.LLMResponse(null,List.of(new ToolCall("call"+n,"run_shell","{\"mode\":\"script\",\"script\":\"echo CLI_OK\"}")))
                    :new LLMClient.LLMResponse("done",null);
        };
        var app=new CliApplication(()->config,(c,o)->new DefaultSessionService(c,model,new JsonSessionRepository(root.resolve("sessions")),Clock.systemUTC()),()->terminal,false);
        return app.run(single?new String[]{"--workspace",root.toString(),"run"}:new String[]{"--workspace",root.toString()});
    }
    @Test void singleTaskTerminalServicesApprovalAndReusesGrant() {
        Terminal terminal=new Terminal(true);assertEquals(0,run(terminal,true));assertEquals(1,terminal.requests);
        assertTrue(String.join("",terminal.output).contains("CLI_OK"));
        assertFalse(String.join("",terminal.output).matches("(?s).*[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}.*"));
    }
    @Test void interactiveTerminalServicesApproval() {
        Terminal terminal=new Terminal(true);assertEquals(0,run(terminal,false));assertEquals(1,terminal.requests);
    }
    @Test void singleTaskWithoutChannelReturnsToolError() {
        Terminal terminal=new Terminal(false);assertEquals(1,run(terminal,true));assertEquals(0,terminal.requests);
    }
}
