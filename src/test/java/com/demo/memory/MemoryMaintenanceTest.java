package com.demo.memory;

import com.demo.memory.context.CompressionState;
import com.demo.memory.context.SessionMemoryState;
import com.demo.model.Message;
import com.demo.session.*;
import com.demo.session.storage.JsonSessionRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MemoryMaintenanceTest {
    @TempDir Path temp;
    static class MutableClock extends Clock {
        Instant value=Instant.parse("2026-10-20T00:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId z){return this;} public Instant instant(){return value;}
    }
    SessionSnapshot snapshot(Instant now,SessionMemoryState state) {
        return new SessionSnapshot(UUID.randomUUID().toString(),"test",temp.toAbsolutePath(),now.minusSeconds(1),now,
                List.of(Message.user("one"),Message.assistant("two")),SessionRun.started(now).finish(SessionRun.Status.COMPLETED,now,"done"),false,2,state);
    }
    @Test void firstRunIsImmediateThenRequiresDirtyAndExactlyTwentyFourHours() throws Exception {
        var clock=new MutableClock();var repository=new JsonSessionRepository(temp.resolve("sessions"));
        var original=snapshot(clock.instant(),SessionMemoryState.empty());repository.save(original);
        var calls=new AtomicInteger();
        var maintenance=new MemoryMaintenance(repository,clock,Duration.ofHours(24),60,(s,t)->calls.incrementAndGet(),false);
        try {
            maintenance.scan();assertEquals(1,calls.get());
            var state=new SessionMemoryState(1,clock.instant(),CompressionState.empty());
            var dirty=new SessionSnapshot(original.id(),original.title(),original.workspace(),original.createdAt(),clock.instant(),original.messages(),original.lastRun(),false,2,state);
            repository.save(dirty);
            clock.value=clock.value.plusSeconds(24*3600-1);maintenance.scan();assertEquals(1,calls.get());
            clock.value=clock.value.plusSeconds(1);maintenance.scan();assertEquals(2,calls.get());
            var clean=new SessionSnapshot(dirty.id(),dirty.title(),dirty.workspace(),dirty.createdAt(),clock.instant(),dirty.messages(),dirty.lastRun(),false,2,
                    new SessionMemoryState(2,clock.instant(),CompressionState.empty()));repository.save(clean);
            clock.value=clock.value.plus(Duration.ofHours(48));maintenance.scan();assertEquals(2,calls.get());
        } finally {assertTrue(maintenance.close(System.nanoTime()+1_000_000_000L));}
    }
    @Test void errorsBackOffFromFiveMinutesWithoutAdvancingSuccessTimestamp() throws Exception {
        var clock=new MutableClock();var repository=new JsonSessionRepository(temp.resolve("sessions"));
        var original=snapshot(clock.instant(),SessionMemoryState.empty());repository.save(original);var calls=new AtomicInteger();
        var maintenance=new MemoryMaintenance(repository,clock,Duration.ofHours(24),60,(s,t)->{calls.incrementAndGet();throw new IllegalStateException("offline");},false);
        try {
            maintenance.scan();maintenance.scan();assertEquals(1,calls.get());
            clock.value=clock.value.plusSeconds(299);maintenance.scan();assertEquals(1,calls.get());
            clock.value=clock.value.plusSeconds(1);maintenance.scan();assertEquals(2,calls.get());
            clock.value=clock.value.plusSeconds(599);maintenance.scan();assertEquals(2,calls.get());
            clock.value=clock.value.plusSeconds(1);maintenance.scan();assertEquals(3,calls.get());
            assertTrue(original.samePersistedState(repository.load(original.id())));
        } finally {assertTrue(maintenance.close(System.nanoTime()+1_000_000_000L));}
    }
    @Test void consolidationRetriesEvenWhenArchivingAlreadyMadeSessionClean() throws Exception {
        var clock=new MutableClock();var repository=new JsonSessionRepository(temp.resolve("sessions"));
        var clean=snapshot(clock.instant(),new SessionMemoryState(2,clock.instant(),CompressionState.empty()));repository.save(clean);
        var calls=new AtomicInteger();var organized=new AtomicInteger();
        var maintenance=new MemoryMaintenance(repository,clock,Duration.ofHours(24),60,(s,t)->organized.incrementAndGet(),false,
                (s,t)->{if(calls.incrementAndGet()==1)throw new IllegalStateException("offline");});
        try{
            maintenance.scan();assertEquals(1,calls.get());assertEquals(0,organized.get());
            clock.value=clock.value.plusSeconds(300);maintenance.scan();assertEquals(2,calls.get());assertEquals(0,organized.get());
        }finally{assertTrue(maintenance.close(System.nanoTime()+1_000_000_000L));}
    }
    @Test void persistedRunningSnapshotMayBelongToAnotherProcessAndIsSkipped() throws Exception {
        var clock=new MutableClock();var repository=new JsonSessionRepository(temp.resolve("sessions"));
        var old=snapshot(clock.instant(),SessionMemoryState.empty());
        var running=new SessionSnapshot(old.id(),old.title(),old.workspace(),old.createdAt(),old.updatedAt(),old.messages(),SessionRun.started(clock.instant()),false);
        repository.save(running);var calls=new AtomicInteger();
        var maintenance=new MemoryMaintenance(repository,clock,Duration.ofHours(24),60,(s,t)->calls.incrementAndGet(),false);
        try{maintenance.scan();assertEquals(0,calls.get());assertTrue(running.samePersistedState(repository.load(running.id())));}
        finally{assertTrue(maintenance.close(System.nanoTime()+1_000_000_000L));}
    }
}
