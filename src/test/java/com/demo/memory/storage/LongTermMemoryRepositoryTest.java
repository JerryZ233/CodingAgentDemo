package com.demo.memory.storage;

import com.demo.memory.model.MemoryEdit;
import com.demo.memory.model.MemoryItem;
import com.demo.memory.model.MemorySource;
import com.demo.memory.model.MemoryUpdate;
import com.demo.storage.StorageFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LongTermMemoryRepositoryTest {
    @TempDir Path temp;
    private final Instant now = Instant.parse("2026-10-20T00:00:00Z");
    private String id() { return UUID.randomUUID().toString(); }
    private MemoryUpdate update(MemoryDocument before, MemoryEdit... edits) {
        return new MemoryUpdate(id(), before.version(), List.of(edits),
                List.of(new MemorySource(id(), id(), null)), "verified project requirement", now);
    }
    private static final class Crash extends RuntimeException { }

    @Test void absentReadDoesNotCreateDirectoriesAndSharedMarkdownNeedsNoLocalIndex() throws Exception {
        Path root = temp.resolve("missing");
        var repository = new LongTermMemoryRepository(root);
        assertFalse(repository.read().exists());
        assertEquals("missing", repository.read().version());
        assertFalse(Files.exists(root));
        assertTrue(repository.changes().isEmpty());
        assertFalse(Files.exists(root));
        Files.createDirectories(root);
        String text = "# Shared rules\n<!-- memory:item id=\"" + id() + "\" kind=\"rule\" -->\n- 中文解释\n<!-- /memory:item -->\n";
        Files.writeString(root.resolve("MEMORY.md"), text);
        assertEquals(text, repository.read().text());
        assertTrue(repository.read().items().get(0).active());
        assertFalse(Files.exists(root.resolve("local")));
    }

    @Test void localUpdatesPreserveUnmanagedTextKeepSourcesLocalAndBackUpOldContent() throws Exception {
        Files.writeString(temp.resolve("MEMORY.md"), "# Notes\r\nUser-owned paragraph.\r\n");
        Files.writeString(temp.resolve(".gitignore"), "custom-rule\n");
        var repository = new LongTermMemoryRepository(temp);
        var before = repository.read();
        String itemId = id();
        var add = update(before, MemoryEdit.add(itemId, MemoryItem.Kind.FACT, "- 项目使用 Java 17。"));
        var added = repository.apply(add);
        assertTrue(added.text().startsWith(before.text()));
        assertEquals(itemId, added.items().get(0).id());
        assertFalse(added.text().contains(add.operationId()));
        assertFalse(added.text().contains(add.sources().get(0).sessionId()));
        assertEquals(before.text(), Files.readString(temp.resolve("local/versions/memory-" + add.operationId() + ".md")));
        assertEquals("custom-rule\n/local/\n", Files.readString(temp.resolve(".gitignore")));
        var updated = repository.apply(update(added, MemoryEdit.update(itemId, MemoryItem.Kind.FACT, "- 项目使用 Java 21。")));
        assertTrue(updated.text().startsWith(before.text()));
        assertEquals("- 项目使用 Java 21。", updated.items().get(0).body());
        var inactive = repository.apply(update(updated, MemoryEdit.invalidate(itemId)));
        assertFalse(inactive.items().get(0).active());
        assertEquals(updated.items().get(0).body(), inactive.items().get(0).body());
        assertEquals(3, repository.changes().size());
        assertEquals(add.sources(), repository.changes().get(0).update().sources());
    }

    @Test void contentHashDetectsManualEditsEvenWhenTimestampIsUnchanged() throws Exception {
        var repository = new LongTermMemoryRepository(temp);
        repository.apply(update(repository.read(), MemoryEdit.add(id(), MemoryItem.Kind.RULE, "- Original")));
        var stale = repository.read();
        Path path = temp.resolve("MEMORY.md");
        FileTime timestamp = Files.getLastModifiedTime(path);
        Files.writeString(path, stale.text() + "manual addition\n");
        Files.setLastModifiedTime(path, timestamp);
        var request = update(stale, MemoryEdit.add(id(), MemoryItem.Kind.FACT, "- New"));
        assertEquals(MemoryStorageException.Reason.CONFLICT,
                assertThrows(MemoryStorageException.class, () -> repository.apply(request)).getReason());
        assertEquals(stale.text() + "manual addition\n", Files.readString(path));
        assertEquals(1, repository.changes().size());
    }

    @Test void eachInterruptionPointRecoversExactlyOnceAndRetryIsIdempotent() {
        for (var step : LongTermMemoryRepository.Step.values()) {
            Path root = temp.resolve(step.name());
            var normal = new LongTermMemoryRepository(root);
            String item = id();
            var before = normal.apply(update(normal.read(), MemoryEdit.add(item, MemoryItem.Kind.FACT, "- Java 17")));
            var request = update(before, MemoryEdit.update(item, MemoryItem.Kind.FACT, "- Java 21"));
            var crashing = new LongTermMemoryRepository(root) {
                @Override protected void checkpoint(Step current) { if (current == step) throw new Crash(); }
            };
            assertThrows(Crash.class, () -> crashing.apply(request));
            var recovered = normal.recover();
            assertEquals("- Java 21", recovered.items().get(0).body(), step.name());
            assertEquals(recovered.version(), normal.apply(request).version());
            assertEquals(2, normal.changes().size());
            assertFalse(Files.exists(root.resolve("local/transactions/memory.json")));
        }
    }

    @Test void recoveryDoesNotOverwriteManualChangesMadeDuringPendingTransaction() throws Exception {
        var repository = new LongTermMemoryRepository(temp);
        var request = update(repository.read(), MemoryEdit.add(id(), MemoryItem.Kind.RULE, "- Proposed"));
        var crashing = new LongTermMemoryRepository(temp) {
            @Override protected void checkpoint(Step step) { if (step == Step.PREPARED) throw new Crash(); }
        };
        assertThrows(Crash.class, () -> crashing.apply(request));
        Files.writeString(temp.resolve("MEMORY.md"), "manual rule\n");
        assertEquals(MemoryStorageException.Reason.CONFLICT,
                assertThrows(MemoryStorageException.class, repository::recover).getReason());
        assertEquals("manual rule\n", Files.readString(temp.resolve("MEMORY.md")));
        assertTrue(Files.exists(temp.resolve("local/transactions/memory.json")));
    }

    @Test void auditedRecoveryPreservesSubsequentManualEdits() throws Exception {
        var repository = new LongTermMemoryRepository(temp);
        var request = update(repository.read(), MemoryEdit.add(id(), MemoryItem.Kind.RULE, "- Proposed"));
        var crashing = new LongTermMemoryRepository(temp) {
            @Override protected void checkpoint(Step step) { if (step == Step.AUDITED) throw new Crash(); }
        };
        assertThrows(Crash.class, () -> crashing.apply(request));
        Files.writeString(temp.resolve("MEMORY.md"), "manual rule\n");
        assertEquals("manual rule\n", repository.recover().text());
        assertEquals("manual rule\n", repository.apply(request).text());
        assertEquals(1, repository.changes().size());
    }

    @Test void ignoreRecordsEvidenceWithoutCreatingAnEmptyMemoryFile() {
        var repository = new LongTermMemoryRepository(temp);
        var request = update(repository.read(), MemoryEdit.ignore());
        assertFalse(repository.apply(request).exists());
        assertEquals(1, repository.changes().size());
        assertFalse(repository.apply(request).exists());
        assertEquals(1, repository.changes().size());
    }

    @Test void conflictingOperationReuseIsRejectedWithoutChangingTheFile() {
        var repository = new LongTermMemoryRepository(temp);
        var request = update(repository.read(), MemoryEdit.add(id(), MemoryItem.Kind.FACT, "- Original"));
        var applied = repository.apply(request);
        var reused = new MemoryUpdate(request.operationId(), applied.version(),
                List.of(MemoryEdit.ignore()), request.sources(), "different operation", now);
        assertEquals(MemoryStorageException.Reason.CONFLICT,
                assertThrows(MemoryStorageException.class, () -> repository.apply(reused)).getReason());
        assertEquals(applied.text(), repository.read().text());
    }

    @Test void malformedMarkersBlockAutomaticEditsAndDoNotLeakDocumentInMessage() throws Exception {
        String item = id();
        String valid = "<!-- memory:item id=\"" + item + "\" kind=\"rule\" -->\n- secret-body\n<!-- /memory:item -->\n";
        for (String invalid : List.of("<!-- memory:item broken -->\nsecret-body", valid + valid,
                valid.replace("kind=\"rule\"", "kind=\"unknown\""), valid.replace("<!-- /memory:item -->", ""))) {
            Files.writeString(temp.resolve("MEMORY.md"), invalid);
            var repository = new LongTermMemoryRepository(temp);
            var error = assertThrows(MemoryStorageException.class, repository::read);
            assertEquals(MemoryStorageException.Reason.MALFORMED_DATA, error.getReason());
            assertFalse(error.getMessage().contains("secret-body"));
            assertEquals(invalid, Files.readString(temp.resolve("MEMORY.md")));
        }
    }

    @Test void readOnlyStorageCanBeReadButCannotMutate() throws Exception {
        Files.writeString(temp.resolve("MEMORY.md"), "# Read-only clone\n");
        var files = new StorageFiles(temp) {
            @Override public Lease lock(String path) throws IOException { throw new java.nio.file.AccessDeniedException(path); }
        };
        var repository = new LongTermMemoryRepository(files);
        var before = repository.read();
        assertEquals("# Read-only clone\n", before.text());
        var request = update(before, MemoryEdit.ignore());
        assertEquals(MemoryStorageException.Reason.IO_FAILURE,
                assertThrows(MemoryStorageException.class, () -> repository.apply(request)).getReason());
        assertFalse(Files.exists(temp.resolve("local")));
    }

    @Test void tornAuditTailIsBackedUpBeforeNextAppend() throws Exception {
        var repository = new LongTermMemoryRepository(temp);
        var before = repository.apply(update(repository.read(), MemoryEdit.add(id(), MemoryItem.Kind.FACT, "- Existing")));
        Path audit = temp.resolve("local/changes.jsonl");
        Files.writeString(audit, "{\"partial", StandardOpenOption.APPEND);
        assertEquals(1, repository.changes().size());
        repository.apply(update(before, MemoryEdit.ignore()));
        assertEquals(2, repository.changes().size());
        try (var files = Files.list(temp.resolve("local/versions"))) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().startsWith("journal-tail-")));
        }
    }

    @Test void failedReplacementLeavesOldKnowledgeAndCanBeRecoveredUsingItsTransaction() throws Exception {
        Files.writeString(temp.resolve("MEMORY.md"), "# Original\n");
        var files = new StorageFiles(temp) {
            @Override protected void moveIntoPlace(Path source, Path target) throws IOException {
                if (target.getFileName().toString().equals("MEMORY.md")) throw new IOException("injected write failure");
                super.moveIntoPlace(source, target);
            }
        };
        var failing = new LongTermMemoryRepository(files);
        var request = update(failing.read(), MemoryEdit.add(id(), MemoryItem.Kind.RULE, "- Preserve API"));
        assertThrows(MemoryStorageException.class, () -> failing.apply(request));
        assertEquals("# Original\n", Files.readString(temp.resolve("MEMORY.md")));
        assertTrue(failing.changes().isEmpty());
        var normal = new LongTermMemoryRepository(temp);
        assertEquals("- Preserve API", normal.recover().items().get(0).body());
        assertEquals(1, normal.changes().size());
    }

    @Test void busyMemoryLockRejectsUpdateWithoutCreatingKnowledge() throws Exception {
        var repository = new LongTermMemoryRepository(temp);
        var request = update(repository.read(), MemoryEdit.ignore());
        try (var lock = new StorageFiles(temp).lock(MemoryDirectory.LOCK)) {
            assertEquals(MemoryStorageException.Reason.LOCKED,
                    assertThrows(MemoryStorageException.class, () -> repository.apply(request)).getReason());
        }
        assertFalse(Files.exists(temp.resolve("MEMORY.md")));
        assertFalse(Files.exists(temp.resolve("local/changes.jsonl")));
    }

    @Test void invalidUtf8IsReportedAsMalformedDataInsteadOfSilentlyReplacingCharacters() throws Exception {
        Files.write(temp.resolve("MEMORY.md"), new byte[] {(byte) 0xc3, (byte) 0x28});
        assertEquals(MemoryStorageException.Reason.MALFORMED_DATA,
                assertThrows(MemoryStorageException.class, () -> new LongTermMemoryRepository(temp).read()).getReason());
    }
}
