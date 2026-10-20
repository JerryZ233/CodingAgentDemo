package com.demo.memory;

import com.demo.llm.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MemoryTelemetryTest {
    @Test void retriesAndMissingUsageAreCountedWithoutInventingZerosOrDoubleCountingCache() {
        var telemetry=new MemoryTelemetry(Clock.systemUTC());
        telemetry.record(new RequestAttempt(1,10,null,false,RequestPurpose.COMPACTION));
        telemetry.record(new RequestAttempt(2,20,new TokenUsage(20,5,25,12L),true,RequestPurpose.COMPACTION));
        telemetry.record(new RequestAttempt(1,2,new TokenUsage(7,2,9,null),true,RequestPurpose.CONSOLIDATION));
        var usage=telemetry.usage().get(RequestPurpose.COMPACTION);
        assertEquals(2,usage.attempts());assertEquals(1,usage.retries());assertEquals(1,usage.withUsage());
        assertEquals(20L,usage.inputTokens());assertEquals(12L,usage.cachedTokens());assertEquals(25L,usage.totalTokens());
        assertFalse(usage.complete());assertFalse(usage.cacheComplete());assertEquals(30,usage.elapsedMillis());
        assertTrue(telemetry.usage().get(RequestPurpose.CONSOLIDATION).complete());
        assertNull(telemetry.usage().get(RequestPurpose.AGENT).inputTokens());
        assertTrue(telemetry.usageText().contains("用量不完整"));
        assertTrue(telemetry.usageText().contains("缓存统计不完整"));
    }
    @Test void boundedProgressHasExplicitGapsAndFiltersOtherProjectsWithoutReplay() {
        var telemetry=new MemoryTelemetry(Clock.systemUTC());
        for(int i=0;i<300;i++)telemetry.event(i%2==0?"a":"b","session",MemoryTelemetry.Origin.BACKGROUND,MemoryTelemetry.Operation.COMPACTION,MemoryTelemetry.Phase.SUCCEEDED);
        var page=telemetry.eventsAfter("a",0);assertTrue(page.gap());assertEquals(128,page.events().size());assertEquals(300,page.nextSequence());
        assertTrue(page.events().stream().allMatch(e->e.projectKey().equals("a")));
        assertTrue(telemetry.eventsAfter("a",page.nextSequence()).events().isEmpty());
    }
    @Test void concurrentPurposesRemainIndependent() throws Exception {
        var telemetry=new MemoryTelemetry(Clock.systemUTC());var executor=Executors.newFixedThreadPool(2);
        try{
            var futures=new ArrayList<Future<?>>();
            for(var purpose:RequestPurpose.values())futures.add(executor.submit(()->{
                for(int i=0;i<50;i++)telemetry.record(new RequestAttempt(1,1,new TokenUsage(1,1,2,0L),true,purpose));
            }));
            for(var future:futures)future.get(5,TimeUnit.SECONDS);
            for(var usage:telemetry.usage().values()){assertEquals(50,usage.attempts());assertEquals(100L,usage.totalTokens());assertTrue(usage.complete());}
        }finally{executor.shutdownNow();}
    }
}
