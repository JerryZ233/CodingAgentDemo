package com.demo.session;

import com.demo.memory.MemoryHarness;
import com.demo.memory.compaction.SummaryFixtures;
import com.demo.memory.MemoryTelemetry;
import com.demo.config.*;
import com.demo.execution.CancellationToken;
import com.demo.llm.*;
import com.demo.model.Message;
import com.demo.session.storage.JsonSessionRepository;
import com.google.gson.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** No network credentials, user home or shared project memory participates in acceptance. */
class LayeredMemoryAcceptanceTest {
    @TempDir Path temp;
    String item(String body) { return "<!-- memory:item id=\""+UUID.randomUUID()+"\" kind=\"rule\" status=\"active\" -->\n"+body+"\n<!-- /memory:item -->\n"; }
    Path project(String name) throws Exception { var p=Files.createDirectory(temp.resolve(name));Files.createDirectory(p.resolve(".git"));return p; }
    DefaultSessionService service(Config config,LLMClient client,JsonSessionRepository sessions,Path global) {
        return new DefaultSessionService(config,client,sessions,Clock.systemUTC(),DefaultSessionService.ClientOwnership.BORROWED,
                new MemoryHarness(config.getMemory(),config.getMaxTokens(),global));
    }
    @Test void defaultHarnessCompactsExtractsRecallsAcrossSessionsAndRestartsAndIsolatesProjects() throws Exception {
        var config=Config.defaults();assertTrue(config.getMemory().enabled());
        var a=project("a");var b=project("b");var global=Files.createDirectory(temp.resolve("global"));
        Files.writeString(global.resolve("MEMORY.md"),item("用户偏好中文解释"));
        var seed=Files.createDirectory(a.resolve(".coding-agent"));Files.writeString(seed.resolve("MEMORY.md"),item("项目使用 Java 17"));
        var sessions=new JsonSessionRepository(temp.resolve("sessions"));
        LLMClient model=SummaryFixtures.client((messages,purpose)->{
            if(purpose==RequestPurpose.COMPACTION){
                var json=JsonParser.parseString(SummaryFixtures.json(SummaryFixtures.sources(messages))).getAsJsonObject();
                json.addProperty("archiveSummary","配置规则：环境变量高于 YAML；保留公共 API");
                return new LLMClient.LLMResponse(json.toString(),null,new TokenUsage(20,5,25,0L));
            }
            if(purpose==RequestPurpose.CONSOLIDATION){
                var data=JsonParser.parseString(messages.get(1).getContent()).getAsJsonObject();
                String history=data.getAsJsonArray("history").get(0).getAsJsonObject().get("id").getAsString();
                return new LLMClient.LLMResponse("{\"operations\":[{\"scope\":\"PROJECT\",\"action\":\"ADD\",\"itemId\":null,\"kind\":\"RULE\",\"body\":\"环境变量高于 YAML\",\"historyIds\":[\""+history+"\"],\"reason\":\"项目确认的配置规则\",\"conflict\":false,\"correctionQuote\":\"\"}]}",null);
            }
            String request=messages.get(messages.size()-1).getContent();
            assertTrue(messages.stream().anyMatch(m->Objects.toString(m.getContent(),"").contains("用户偏好中文解释")));
            if(request.equals("other project"))assertTrue(messages.stream().noneMatch(m->Objects.toString(m.getContent(),"").contains("环境变量高于 YAML")));
            if(request.equals("new session")){
                assertTrue(messages.stream().anyMatch(m->Objects.toString(m.getContent(),"").contains("环境变量高于 YAML")));
                assertTrue(messages.stream().noneMatch(m->Objects.toString(m.getContent(),"").contains("private old transcript")));
            }
            if(request.equals("resume"))assertTrue(messages.stream().anyMatch(m->Objects.toString(m.getContent(),"").contains("Do not modify public API")));
            return new LLMClient.LLMResponse("done",null,new TokenUsage(10,2,12,0L));
        });
        String original;
        try(var service=service(config,model,sessions,global)){
            original=service.createSession(a).id();
            var first=service.runTurn(original,"Do not modify public API; private old transcript");
            var raw=first.messages().stream().map(Message::toJson).toList();
            assertTrue(service.memoryCommand(original,"show").contains("本地来源不可用"));
            service.requestMemoryMaintenance(original,"compact").toCompletableFuture().get(10,TimeUnit.SECONDS);
            service.requestMemoryMaintenance(original,"consolidate").toCompletableFuture().get(10,TimeUnit.SECONDS);
            assertEquals(raw,service.getSession(original).messages().stream().map(Message::toJson).toList());
            var next=service.createSession(a);assertEquals(SessionRun.Status.COMPLETED,service.runTurn(next.id(),"new session").lastRun().status());
            var hits=service.searchHistory(next.id(),"配置");assertEquals(1,hits.get("matched_count").getAsInt());
            String history=hits.getAsJsonArray("results").get(0).getAsJsonObject().get("history_id").getAsString();
            assertEquals("AVAILABLE",service.readHistory(next.id(),history,true).get("source_status").getAsString());
            var other=service.createSession(b);service.runTurn(other.id(),"other project");
            assertEquals(0,service.searchHistory(other.id(),"配置").get("matched_count").getAsInt());
            assertTrue(service.memoryEventsAfter(other.id(),0).events().isEmpty());
            assertTrue(service.modelUsage().get(RequestPurpose.AGENT).complete());
            assertNull(service.modelUsage().get(RequestPurpose.CONSOLIDATION).inputTokens());
            assertFalse(service.modelUsage().get(RequestPurpose.CONSOLIDATION).complete());
            assertTrue(service.memoryEventsAfter(original,0).events().stream().anyMatch(e->e.phase()==MemoryTelemetry.Phase.SUCCEEDED));
        }
        try(var service=service(config,model,sessions,global)){
            var result=service.runTurn(original,"resume");assertEquals(SessionRun.Status.COMPLETED,result.lastRun().status());
            assertTrue(result.messages().get(0).getContent().contains("private old transcript"));
        }
    }
    @Test void cloneReadsSharedMarkdownWithoutLocalHistoryAndDisabledModeDoesNotTouchMemory() throws Exception {
        var project=project("clone");var global=temp.resolve("global");
        var dir=Files.createDirectory(project.resolve(".coding-agent"));Files.writeString(dir.resolve("MEMORY.md"),item("clone rule"));
        var d=MemoryConfig.defaults();var noBackground=new MemoryConfig(true,d.contextWindowTokens(),d.safetyMarginTokens(),d.compactTriggerRatio(),d.compactTargetRatio(),d.globalBudgetTokens(),d.projectBudgetTokens(),d.summaryBudgetTokens(),d.retainRecentBlocks(),
                new MemoryConfig.Consolidation(false,5,10),new MemoryConfig.Maintenance(false,24,60));
        var config=Config.defaults().withMemory(noBackground);var sessions=new JsonSessionRepository(temp.resolve("sessions"));
        try(var service=service(config,(m,t)->new LLMClient.LLMResponse("done",null),sessions,global)){
            var id=service.createSession(project).id();assertTrue(service.memoryCommand(id,"show").contains("本地来源不可用"));
            assertFalse(Files.exists(dir.resolve("local")));assertFalse(Files.exists(global));
        }
        try(var service=service(config.withMemory(noBackground.withEnabled(false)),(m,t)->{
            assertFalse(t.contains("memory_"));assertTrue(m.stream().noneMatch(x->Objects.toString(x.getContent(),"").contains("clone rule")));
            return new LLMClient.LLMResponse("done",null);
        },sessions,global)){
            var id=service.createSession(project).id();service.runTurn(id,"plain");
            assertFalse(Files.exists(dir.resolve("local")));assertFalse(Files.exists(global));
        }
    }
}
