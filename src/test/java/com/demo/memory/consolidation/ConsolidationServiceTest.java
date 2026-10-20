package com.demo.memory.consolidation;

import com.demo.memory.model.HistoryEntry;
import com.demo.memory.model.MemoryEdit;
import com.demo.memory.model.MemoryItem;
import com.demo.memory.model.MemoryProposal;
import com.demo.memory.model.MemorySource;
import com.demo.memory.model.MemoryUpdate;
import com.demo.config.*;
import com.demo.execution.*;
import com.demo.llm.*;
import com.demo.memory.storage.*;
import com.demo.model.*;
import com.demo.session.*;
import com.demo.session.storage.*;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ConsolidationServiceTest {
    @TempDir Path temp;
    Path project, global;
    JsonSessionRepository sessions;
    final Instant now = Instant.parse("2026-10-20T00:00:00Z");
    final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    final MemoryConfig config = MemoryConfig.defaults().withEnabled(true);
    String id() { return UUID.randomUUID().toString(); }
    @BeforeEach void setup() throws Exception {
        project = Files.createDirectory(temp.resolve("project")); Files.createDirectory(project.resolve(".git"));
        global = temp.resolve("global"); sessions = new JsonSessionRepository(temp.resolve("sessions"));
    }
    SessionSnapshot raw(Path workspace) {
        var snapshot = new SessionSnapshot(id(), "test", workspace, now, now,
                List.of(Message.user("项目配置迁移到 Java 21；以后所有项目中文解释"), Message.assistant("完成")), SessionRun.started(now).finish(SessionRun.Status.COMPLETED,now,"done"), false);
        sessions.save(snapshot); return snapshot;
    }
    HistoryEntry archive(String text) {
        var snapshot = raw(project); var refs = snapshot.messages().stream().map(Message::getId).toList();
        var entry = new HistoryEntry(id(), id(), snapshot.id(), snapshot.contentRevision(), refs, null, text, text,
                List.of("Java"), refs, now, "test");
        new ArchiveCommitter(sessions,new HistoryRepository(project.resolve(".coding-agent"))).commit(snapshot,entry,now);
        return entry;
    }
    String operation(String scope, String action, String item, String body, String history, boolean conflict, String quote) {
        var op = new JsonObject(); op.addProperty("scope",scope); op.addProperty("action",action);
        op.add("itemId",item == null ? JsonNull.INSTANCE : new JsonPrimitive(item));
        op.add("kind",action.equals("INVALIDATE") || action.equals("IGNORE") ? JsonNull.INSTANCE : new JsonPrimitive("FACT"));
        op.add("body",body == null ? JsonNull.INSTANCE : new JsonPrimitive(body));
        var refs = new JsonArray(); refs.add(history); op.add("historyIds",refs);
        op.addProperty("reason","依据已提交历史"); op.addProperty("conflict",conflict); op.addProperty("correctionQuote",quote);
        return op.toString();
    }
    ConsolidationService service(LLMClient client) {
        return new ConsolidationService(project,global,config,new ConsolidationModel(client,config,4096),clock);
    }
    ConsolidationService service(String ops) { return service((messages,tools) -> { assertNull(tools); return new LLMClient.LLMResponse("{\"operations\":["+ops+"]}",null); }); }
    LongTermMemoryRepository local() { return new LongTermMemoryRepository(project.resolve(".coding-agent")); }
    ConsolidationRepository state() { return new ConsolidationRepository(project.resolve(".coding-agent")); }

    @Test void incrementalBatchHasExplicitCursorAndDoesNotDeleteHistoryOrRepeatModelCalls() {
        var entry=archive("项目使用 Java 21"); var calls=new AtomicInteger();
        var service=service((messages,tools)-> { calls.incrementAndGet(); return new LLMClient.LLMResponse("{\"operations\":["+operation("PROJECT","ADD",null,"Java 21",entry.id(),false,"")+"]}",null); });
        assertEquals(0,service.consolidate(false,CancellationToken.none()));
        assertEquals(1,service.consolidate(true,CancellationToken.none()));
        assertEquals(1,local().read().items().size()); assertEquals(List.of(entry.id()),state().read().processed());
        assertEquals(0,service.consolidate(true,CancellationToken.none())); assertEquals(1,calls.get());
        assertEquals(1,new HistoryRepository(project.resolve(".coding-agent")).read().entries().size());
    }
    @Test void multipleAutomaticEditsUsePreparedVersionChainAndExactDuplicatesAreIgnored() {
        var e=archive("Java 21; UTF-8");
        var service=service(operation("PROJECT","ADD",null,"Java 21",e.id(),false,"")+","+operation("PROJECT","ADD",null,"UTF-8",e.id(),false,"")+","+operation("PROJECT","ADD",null,"Java 21",e.id(),false,""));
        assertEquals(1,service.consolidate(true,CancellationToken.none())); assertEquals(2,local().read().items().size());
        assertTrue(state().read().prepared().isEmpty()); assertTrue(state().read().batch().isEmpty());
    }
    @Test void globalIsPendingAndGenericApprovalCannotWrite() {
        var e=archive("用户希望所有项目中文解释"); var service=service(operation("GLOBAL","ADD",null,"中文解释",e.id(),false,""));
        service.consolidate(true,CancellationToken.none()); assertFalse(Files.exists(global));
        var display=service.display().orElseThrow();
        assertFalse(service.resolve(display,"采用新的",CancellationToken.none()));
        assertFalse(service.resolve(display,"好的",CancellationToken.none())); assertFalse(Files.exists(global));
        assertTrue(service.resolve(display,"批准全局记忆",CancellationToken.none()));
        assertEquals("中文解释",new LongTermMemoryRepository(global).read().items().get(0).body());
        assertTrue(service.display().isEmpty()); assertEquals("APPROVED",state().read().decisions().get(0).outcome());
        assertThrows(IllegalStateException.class,()->service.resolve(display,"批准全局记忆",CancellationToken.none()));
    }
    @Test void conflictPreservesOldUntilExactDisplayedCandidateIsApproved() {
        var snapshot=raw(project); var service=service("unused");
        service.propose(snapshot,MemoryProposal.Scope.PROJECT,MemoryEdit.add(id(),MemoryItem.Kind.FACT,"Java 17"),"初始");
        service.resolve(service.display().orElseThrow(),"采用新的",CancellationToken.none());
        String target=local().read().items().get(0).id(); var e=archive("版本不明，Java 21 或 17");
        var extraction=service(operation("PROJECT","UPDATE",target,"Java 21",e.id(),true,""));
        extraction.consolidate(true,CancellationToken.none()); assertEquals("Java 17",local().read().items().get(0).body());
        var display=extraction.display().orElseThrow();
        extraction.propose(snapshot,MemoryProposal.Scope.PROJECT,MemoryEdit.add(id(),MemoryItem.Kind.RULE,"保留 API"),"后来候选");
        assertFalse(extraction.resolve(display,"看着办",CancellationToken.none()));
        assertTrue(extraction.resolve(display,"保留旧的",CancellationToken.none()));
        assertEquals("Java 17",local().read().items().get(0).body());
        assertEquals("保留 API",extraction.display().orElseThrow().proposal().edit().body());
    }
    @Test void staleApprovalFailsAndRedisplayShowsFreshState() {
        var service=service("unused"); var snapshot=raw(project);
        service.propose(snapshot,MemoryProposal.Scope.PROJECT,MemoryEdit.add(id(),MemoryItem.Kind.FACT,"one"),"用户");
        var display=service.display().orElseThrow();
        local().apply(new MemoryUpdate(id(),"missing",List.of(MemoryEdit.add(id(),MemoryItem.Kind.FACT,"manual")),List.of(new MemorySource(snapshot.id(),snapshot.messages().get(0).getId(),null)),"外部",now));
        assertThrows(IllegalStateException.class,()->service.resolve(display,"采用新的",CancellationToken.none()));
        assertTrue(service.resolve(service.display().orElseThrow(),"采用新的",CancellationToken.none()));
        assertEquals(2,local().read().items().size());
    }
    @Test void preparedWriteRecoveryIsIdempotentAfterMemoryAppliedBeforeCursor() {
        var e=archive("Java 21"); var proposal=new MemoryProposal(id(),MemoryProposal.Scope.PROJECT,"missing",
                MemoryEdit.add(id(),MemoryItem.Kind.FACT,"Java 21"),List.of(new MemorySource(e.sessionId(),null,e.id())),"来源",now,true);
        state().save(state().read(),List.of(),List.of(),List.of(proposal),List.of(e.id()));
        local().apply(proposal.update()); service("unused").recover(CancellationToken.none());
        assertEquals(List.of(e.id()),state().read().processed()); assertEquals(1,local().changes().size());
    }
    @Test void projectJournalCannotReplayGlobalApproval() {
        var snapshot=raw(project); var p=new MemoryProposal(id(),MemoryProposal.Scope.GLOBAL,"missing",MemoryEdit.add(id(),MemoryItem.Kind.RULE,"global"),List.of(new MemorySource(snapshot.id(),snapshot.messages().get(0).getId(),null)),"test",now,false);
        assertThrows(IllegalArgumentException.class,()->state().save(state().read(),List.of(),List.of(),List.of(p),List.of()));
        assertFalse(Files.exists(global));
    }
    @Test void invalidOrForeignSourcesNeverAdvanceCursorAndFormatRepairIsBounded() {
        archive("Java 21"); var calls=new AtomicInteger();
        var service=service((messages,tools)-> {calls.incrementAndGet(); return new LLMClient.LLMResponse("{\"operations\":["+operation("PROJECT","ADD",null,"Java 21",id(),false,"")+"]}",null);});
        assertThrows(IllegalArgumentException.class,()->service.consolidate(true,CancellationToken.none()));
        assertEquals(2,calls.get()); assertTrue(state().read().processed().isEmpty()); assertFalse(local().read().exists());
    }
    @Test void cancellationAfterModelDoesNotPrepareOrAdvance() {
        var e=archive("Java 21"); var cancel=new CancellationSource();
        var service=service((messages,tools)->{cancel.cancel();return new LLMClient.LLMResponse("{\"operations\":["+operation("PROJECT","ADD",null,"Java 21",e.id(),false,"")+"]}",null);});
        assertThrows(OperationCancelledException.class,()->service.consolidate(true,cancel.token()));
        assertTrue(state().read().processed().isEmpty()); assertTrue(state().read().prepared().isEmpty());
    }
    @Test void immediateProposalRejectsForeignProjectAndUnknownTarget() throws Exception {
        var other=Files.createDirectory(temp.resolve("other")); Files.createDirectory(other.resolve(".git"));
        var service=service("unused");
        assertThrows(IllegalArgumentException.class,()->service.propose(raw(other),MemoryProposal.Scope.PROJECT,MemoryEdit.add(id(),MemoryItem.Kind.FACT,"foreign"),"bad"));
        assertThrows(IllegalArgumentException.class,()->service.propose(raw(project),MemoryProposal.Scope.PROJECT,MemoryEdit.invalidate(id()),"bad"));
    }
    @Test void budgetFailureLeavesCandidateAndHistoryProcessed() {
        var e=archive("oversized reusable fact"); var service=service(operation("PROJECT","ADD",null,"中".repeat(4000),e.id(),false,""));
        service.consolidate(true,CancellationToken.none()); assertFalse(local().read().exists());
        assertEquals(List.of(e.id()),state().read().processed()); var display=service.display().orElseThrow();
        assertThrows(IllegalArgumentException.class,()->service.resolve(display,"采用新的",CancellationToken.none()));
    }
    @Test void explicitCorrectionCanUpdateAndInvalidationPreservesOldBody() {
        var snapshot=raw(project); var service=service("unused");
        service.propose(snapshot,MemoryProposal.Scope.PROJECT,MemoryEdit.add(id(),MemoryItem.Kind.FACT,"Java 17"),"initial");
        service.resolve(service.display().orElseThrow(),"采用新的",CancellationToken.none());String target=local().read().items().get(0).id();
        var e=archive("已迁移到 Java 21，替代旧版本 Java 17");
        service(operation("PROJECT","UPDATE",target,"Java 21",e.id(),false,"已迁移到 Java 21")).consolidate(true,CancellationToken.none());
        assertEquals("Java 21",local().read().items().get(0).body());
        service.propose(snapshot,MemoryProposal.Scope.PROJECT,MemoryEdit.invalidate(target),"用户要求失效");
        service.resolve(service.display().orElseThrow(),"采用新的",CancellationToken.none());
        assertFalse(local().read().items().get(0).active());assertEquals("Java 21",local().read().items().get(0).body());
    }
    @Test void globalWriteAlreadyCommittedBeforeCandidateRemovalIsRecognizedWithoutDuplicateWrite() {
        var service=service("unused");var snapshot=raw(project);
        var p=service.propose(snapshot,MemoryProposal.Scope.GLOBAL,MemoryEdit.add(id(),MemoryItem.Kind.PREFERENCE,"中文"),"explicit");
        new LongTermMemoryRepository(global).apply(p.update());
        assertTrue(service.display().isEmpty());assertEquals(1,new LongTermMemoryRepository(global).changes().size());
        assertEquals("APPROVED_GLOBAL_RECOVERED",state().read().decisions().get(0).outcome());
    }
    @Test void ignoredHistoryStillGetsDurableOutcomeAndNoMemoryDocument() {
        var e=archive("一次编译错误已经修复");var service=service(operation("PROJECT","IGNORE",null,null,e.id(),false,""));
        service.consolidate(true,CancellationToken.none());assertFalse(local().read().exists());
        assertEquals(1,local().changes().size());assertEquals(List.of(e.id()),state().read().processed());
    }
    @Test void duplicateJsonFieldsAndToolCallsAreRejectedWithoutWrites() {
        archive("stable");var count=new AtomicInteger();
        var service=service((m,t)->{count.incrementAndGet();return new LLMClient.LLMResponse("{\"operations\":[],\"operations\":[]}",null);});
        assertThrows(IllegalArgumentException.class,()->service.consolidate(true,CancellationToken.none()));assertEquals(2,count.get());
        var tools=service((m,t)->new LLMClient.LLMResponse(null,List.of(new ToolCall("x","run_shell","{}"))));
        assertThrows(IllegalArgumentException.class,()->tools.consolidate(true,CancellationToken.none()));assertTrue(state().read().processed().isEmpty());
    }
    @Test void stalePreparedOperationBecomesCandidateWithoutOverwritingNewDocument() {
        var e=archive("Java 21");var proposal=new MemoryProposal(id(),MemoryProposal.Scope.PROJECT,"missing",
                MemoryEdit.add(id(),MemoryItem.Kind.FACT,"Java 21"),List.of(new MemorySource(e.sessionId(),null,e.id())),"history",now,true);
        state().save(state().read(),List.of(),List.of(),List.of(proposal),List.of(e.id()));
        local().apply(new MemoryUpdate(id(),"missing",List.of(MemoryEdit.add(id(),MemoryItem.Kind.FACT,"manual fact")),proposal.sources(),"manual",now));
        var service=service("unused");service.recover(CancellationToken.none());
        assertEquals(1,local().read().items().size());assertEquals(List.of(e.id()),state().read().processed());
        assertEquals(proposal.id(),service.display().orElseThrow().proposal().id());
        service.resolve(service.display().orElseThrow(),"采用新的",CancellationToken.none());assertEquals(2,local().read().items().size());
    }
    @Test void repeatedImmediateProposalFromSameUserMessageIsDeduplicated() {
        var service=service("unused");var snapshot=raw(project);
        var first=service.propose(snapshot,MemoryProposal.Scope.PROJECT,MemoryEdit.add(id(),MemoryItem.Kind.FACT,"Java 21"),"first");
        var duplicate=service.propose(snapshot,MemoryProposal.Scope.PROJECT,MemoryEdit.add(id(),MemoryItem.Kind.FACT,"Java 21"),"second attempt");
        assertEquals(first.id(),duplicate.id());assertEquals(1,state().read().pending().size());
    }
    @Test void staleCandidateCasCannotPerformGlobalWriteSideEffect() {
        var service=service("unused");var snapshot=raw(project);
        var proposal=service.propose(snapshot,MemoryProposal.Scope.GLOBAL,MemoryEdit.add(id(),MemoryItem.Kind.RULE,"global rule"),"user");
        var before=state().read();state().save(before,before.processed(),List.of(),List.of(),List.of());
        var conflict=assertThrows(MemoryStorageException.class,()->state().save(before,before.processed(),List.of(),List.of(),List.of(),List.of(),
                ()->new LongTermMemoryRepository(global).apply(proposal.update())));
        assertEquals(MemoryStorageException.Reason.CONFLICT,conflict.getReason());assertFalse(Files.exists(global));
    }
}
