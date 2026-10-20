package com.demo.session;

import com.demo.memory.MemoryHarness;
import com.demo.memory.compaction.SummaryFixtures;
import com.demo.memory.model.HistoryEntry;
import com.demo.config.*;
import com.demo.execution.*;
import com.demo.llm.*;
import com.demo.memory.storage.*;
import com.demo.model.Message;
import com.demo.session.storage.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class MemoryMaintenanceIntegrationTest {
    @TempDir Path temp;
    JsonSessionRepository repository;
    final Instant now=Instant.parse("2026-10-20T00:00:00Z");
    String id(){return UUID.randomUUID().toString();}
    @BeforeEach void setup() throws Exception {Files.createDirectory(temp.resolve(".git"));repository=new JsonSessionRepository(temp.resolve("sessions"));}
    MemoryConfig memory(boolean background,boolean consolidate,int threshold){
        var d=MemoryConfig.defaults();
        return new MemoryConfig(true,d.contextWindowTokens(),d.safetyMarginTokens(),d.compactTriggerRatio(),d.compactTargetRatio(),d.globalBudgetTokens(),d.projectBudgetTokens(),d.summaryBudgetTokens(),d.retainRecentBlocks(),
                new MemoryConfig.Consolidation(consolidate,threshold,10),new MemoryConfig.Maintenance(background,24,60));
    }
    DefaultSessionService service(MemoryConfig memory,LLMClient client){
        var config=Config.defaults().withMemory(memory);
        return new DefaultSessionService(config,client,repository,Clock.fixed(now,ZoneOffset.UTC),DefaultSessionService.ClientOwnership.BORROWED,
                new MemoryHarness(memory,config.getMaxTokens(),temp.resolve("global")));
    }
    SessionSnapshot raw(){
        var s=new SessionSnapshot(id(),"old",temp.toAbsolutePath(),now,now,List.of(Message.user("保留 API"),Message.assistant("完成")),SessionRun.started(now).finish(SessionRun.Status.COMPLETED,now,"done"),false);
        repository.save(s);return s;
    }
    void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(!condition.getAsBoolean() && System.nanoTime()<deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean());
    }
    LLMClient summaryClient(java.util.function.BiFunction<List<Message>,RequestPurpose,LLMClient.LLMResponse> action){return SummaryFixtures.client(action);}

    @Test void startupImmediatelyBackfillsOldSessionsWithoutChangingRawContentOrDirtyRevision() throws Exception {
        var old=raw();var calls=new AtomicInteger();
        try(var service=service(memory(true,false,5),summaryClient((messages,purpose)->{
            assertEquals(RequestPurpose.COMPACTION,purpose);calls.incrementAndGet();
            return new LLMClient.LLMResponse(SummaryFixtures.json(SummaryFixtures.sources(messages)),null);
        }))){
            await(()->{try{return !service.getSession(old.id()).dirty();}catch(SessionException busy){return false;}});var organized=service.getSession(old.id());
            assertEquals(old.contentRevision(),organized.contentRevision());
            assertEquals(old.messages().stream().map(Message::toJson).toList(),organized.messages().stream().map(Message::toJson).toList());
            assertEquals(1,organized.memoryState().compression().version());assertEquals(1,calls.get());
        }
    }
    @Test void backgroundModelMayOverlapForegroundButCannotCommitOverNewRawMessages() throws Exception {
        var old=raw();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var service=service(memory(true,false,5),summaryClient((messages,purpose)->{
            if(purpose==RequestPurpose.COMPACTION){entered.countDown();try{assertTrue(release.await(8,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}
                return new LLMClient.LLMResponse(SummaryFixtures.json(SummaryFixtures.sources(messages)),null);}
            return new LLMClient.LLMResponse("new answer",null);
        }))){
            try{
                assertTrue(entered.await(5,TimeUnit.SECONDS));var finished=service.runTurn(old.id(),"new request");
                assertEquals(SessionRun.Status.COMPLETED,finished.lastRun().status());
            }finally{release.countDown();}
        }
        var actual=repository.load(old.id());assertEquals(4,actual.messages().size());
        assertEquals(0,actual.memoryState().compression().version());assertTrue(actual.dirty());
    }
    @Test void closeCancelsBackgroundModelAndDoesNotStartExitCompaction() throws Exception {
        var old=raw();var entered=new CountDownLatch(1);var cancelled=new CountDownLatch(1);var calls=new AtomicInteger();
        LLMClient client=new LLMClient(){
            public LLMResponse sendMessage(List<Message> m,String t){throw new AssertionError();}
            public LLMResponse sendMessage(List<Message> m,String t,CancellationToken token,java.util.function.Consumer<LLMFailure> retry,RequestPurpose purpose){
                calls.incrementAndGet();entered.countDown();
                try(var registration=token.onCancellation(cancelled::countDown)){
                    try{assertTrue(cancelled.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}
                    token.throwIfCancellationRequested();throw new AssertionError();
                }
            }
        };
        var service=service(memory(true,false,5),client);
        assertTrue(entered.await(5,TimeUnit.SECONDS));service.close();service.close();
        assertTrue(old.samePersistedState(repository.load(old.id())));assertEquals(1,calls.get());
    }
    @Test void manualCompactionAndConsolidationAreAsyncAndUseSeparatePurposes() throws Exception {
        var old=raw();var purposes=new CopyOnWriteArrayList<RequestPurpose>();
        try(var service=service(memory(false,true,5),summaryClient((messages,purpose)->{
            purposes.add(purpose);
            if(purpose==RequestPurpose.COMPACTION)return new LLMClient.LLMResponse(SummaryFixtures.json(SummaryFixtures.sources(messages)),null);
            var data=com.google.gson.JsonParser.parseString(messages.get(1).getContent()).getAsJsonObject();
            String history=data.getAsJsonArray("history").get(0).getAsJsonObject().get("id").getAsString();
            return new LLMClient.LLMResponse("{\"operations\":[{\"scope\":\"PROJECT\",\"action\":\"ADD\",\"itemId\":null,\"kind\":\"RULE\",\"body\":\"保留公共 API\",\"historyIds\":[\""+history+"\"],\"reason\":\"明确约束\",\"conflict\":false,\"correctionQuote\":\"\"}]}",null);
        }))){
            service.requestMemoryMaintenance(old.id(),"compact").toCompletableFuture().get(5,TimeUnit.SECONDS);
            service.requestMemoryMaintenance(old.id(),"consolidate").toCompletableFuture().get(5,TimeUnit.SECONDS);
            assertEquals(List.of(RequestPurpose.COMPACTION,RequestPurpose.CONSOLIDATION),purposes);
            assertTrue(service.memoryCommand(old.id(),"show").contains("保留公共 API"));
        }
    }
    @Test void failedExtractionAfterSavedCodingDoesNotFailCodingAndCanRetryLater() {
        var old=raw();var refs=old.messages().stream().map(Message::getId).toList();
        var e=new HistoryEntry(id(),id(),old.id(),old.contentRevision(),refs,null,"保留 API","保留 API",List.of(),refs,now,"test");
        new ArchiveCommitter(repository,new HistoryRepository(temp.resolve(".coding-agent"))).commit(old,e,now);
        var calls=new AtomicInteger();
        try(var service=service(memory(false,true,1),summaryClient((messages,purpose)->{
            if(purpose==RequestPurpose.CONSOLIDATION){calls.incrementAndGet();throw new IllegalStateException("offline fake failure");}
            return new LLMClient.LLMResponse("done",null);
        }))){
            var current=service.createSession(temp);var result=service.runTurn(current.id(),"code");
            assertEquals(SessionRun.Status.COMPLETED,result.lastRun().status());assertFalse(result.unsaved());assertEquals(1,calls.get());
            assertTrue(new ConsolidationRepository(temp.resolve(".coding-agent")).read().processed().isEmpty());
        }
    }
    @Test void disabledModeStartsNoMaintenanceOrMemoryWrites() {
        var old=raw();try(var service=new DefaultSessionService(com.demo.testing.TestConfigs.defaults(),(m,t)->{throw new AssertionError();},repository,Clock.fixed(now,ZoneOffset.UTC))){
            assertEquals("记忆功能未开启。",service.requestMemoryMaintenance(old.id(),"compact").toCompletableFuture().join());
            assertFalse(Files.exists(temp.resolve(".coding-agent")));
        }
    }
}
