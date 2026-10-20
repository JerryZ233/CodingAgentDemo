package com.demo.cli;

import com.demo.memory.MemoryHarness;
import com.demo.memory.compaction.SummaryFixtures;
import com.demo.config.*;
import com.demo.llm.*;
import com.demo.session.*;
import com.demo.session.storage.JsonSessionRepository;
import java.io.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CliMemoryProgressIntegrationTest {
    @TempDir Path temp;
    @Test void idlePumpShowsMaintenanceOnceWithoutPublishingRawSourceOrInternalIds() throws Exception {
        Files.createDirectory(temp.resolve(".git"));var d=MemoryConfig.defaults();
        var memory=new MemoryConfig(true,d.contextWindowTokens(),d.safetyMarginTokens(),d.compactTriggerRatio(),d.compactTargetRatio(),d.globalBudgetTokens(),d.projectBudgetTokens(),d.summaryBudgetTokens(),d.retainRecentBlocks(),
                new MemoryConfig.Consolidation(false,5,10),new MemoryConfig.Maintenance(false,24,60));
        var config=Config.defaults().withMemory(memory);var output=new StringWriter();
        var model=SummaryFixtures.client((m,p)->new LLMClient.LLMResponse(p==RequestPurpose.COMPACTION?SummaryFixtures.json(SummaryFixtures.sources(m)):"done",null));
        try(var service=new DefaultSessionService(config,model,new JsonSessionRepository(temp.resolve("sessions")),Clock.systemUTC(),DefaultSessionService.ClientOwnership.BORROWED,
                    new MemoryHarness(memory,config.getMaxTokens(),temp.resolve("global")));
            var terminal=new PlainTerminalIO(new StringReader(""),new PrintWriter(output),new PrintWriter(output));
            var pump=new RunEventPump(service,new CliRenderer(terminal))){
            var id=service.createSession(temp).id();service.runTurn(id,"private-memory-source");pump.watchMemory(id);
            service.requestMemoryMaintenance(id,"compact").toCompletableFuture().get(5,TimeUnit.SECONDS);pump.poll();
            assertTrue(output.toString().contains("手动会话整理已完成"));
            assertFalse(output.toString().contains("private-memory-source"));assertFalse(output.toString().contains(id));
            int length=output.toString().length();pump.poll();assertEquals(length,output.toString().length());
            var status=service.memoryCommand(id,"status");assertTrue(status.contains("COMPACTION"));assertTrue(status.contains("未知"));
        }
    }
}
