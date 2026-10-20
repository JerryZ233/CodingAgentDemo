package com.demo.cli;

import com.demo.memory.MemoryHarness;
import com.demo.config.*;
import com.demo.llm.*;
import com.demo.memory.storage.*;
import com.demo.model.*;
import com.demo.session.*;
import com.demo.session.storage.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CliMemoryProposalIntegrationTest {
    @TempDir Path temp;
    @Test void toolProposalIsDisplayedOnceAndOnlyExplicitGlobalUserReplyApprovesWithoutModelCall() throws Exception {
        Files.createDirectory(temp.resolve(".git"));var d=MemoryConfig.defaults();
        var memory=new MemoryConfig(true,d.contextWindowTokens(),d.safetyMarginTokens(),d.compactTriggerRatio(),d.compactTargetRatio(),d.globalBudgetTokens(),d.projectBudgetTokens(),d.summaryBudgetTokens(),d.retainRecentBlocks(),
                new MemoryConfig.Consolidation(false,5,10),new MemoryConfig.Maintenance(false,24,60));
        var config=Config.defaults().withMemory(memory);var repository=new JsonSessionRepository(temp.resolve("sessions"));var calls=new AtomicInteger();
        LLMClient client=(messages,tools)->{
            assertTrue(tools.contains("memory_propose"));assertTrue(tools.contains("memory_resolve_latest"));
            if(calls.getAndIncrement()==0)return new LLMClient.LLMResponse(null,List.of(new ToolCall("proposal","memory_propose",
                    "{\"scope\":\"GLOBAL\",\"action\":\"ADD\",\"kind\":\"PREFERENCE\",\"body\":\"中文解释\",\"reason\":\"用户明确偏好\"}")));
            return new LLMClient.LLMResponse("已提出候选",null);
        };
        var output=new StringWriter();
        try(var service=new DefaultSessionService(config,client,repository,Clock.systemUTC(),DefaultSessionService.ClientOwnership.BORROWED,new MemoryHarness(memory,config.getMaxTokens(),temp.resolve("global")));
            var terminal=new PlainTerminalIO(new StringReader(""),new PrintWriter(output),new PrintWriter(output));
            var pump=new RunEventPump(service,new CliRenderer(terminal))){
            var s=service.createSession(temp);service.runTurn(s.id(),"以后所有项目用中文解释");
            var before=service.getSession(s.id());assertFalse(Files.exists(temp.resolve("global")));
            var renderer=new CliRenderer(terminal);var controller=new CliController(service,temp,renderer,pump,new CliShutdownCoordinator(service,pump,terminal,renderer));
            controller.approvalChannel(true);controller.initialize(CliOptions.parse(new String[]{"--session",s.id()}));
            controller.prompt();assertTrue(output.toString().contains("待确认记忆（GLOBAL）"));int length=output.toString().length();
            controller.prompt();assertEquals(length,output.toString().length());
            controller.accept(TerminalIO.Input.line("/memory approve project"));assertFalse(Files.exists(temp.resolve("global")));
            controller.accept(TerminalIO.Input.line("批准全局记忆"));
            assertEquals("中文解释",new LongTermMemoryRepository(temp.resolve("global")).read().items().get(0).body());
            assertEquals(2,calls.get());assertTrue(before.samePersistedState(service.getSession(s.id())));
        }
    }
    @Test void parserRecognizesAllManualMaintenanceCommands() {
        var parser=new CliCommandParser();
        for(String command:List.of("show","status","pending","compact","consolidate"))assertEquals(new CliCommand.MemoryAction(command),parser.parse("/memory "+command));
        assertEquals(new CliCommand.MemoryResolve("批准全局记忆"),parser.parse("/memory approve global"));
        assertEquals(new CliCommand.MemoryResolve("保留旧的"),parser.parse("/memory resolve 保留旧的"));
    }
}
