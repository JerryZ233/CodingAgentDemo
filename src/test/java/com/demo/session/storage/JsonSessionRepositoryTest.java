package com.demo.session.storage;

import com.demo.session.SessionSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class JsonSessionRepositoryTest {
    @TempDir Path temp;

    private SessionSnapshot empty(String id, String title) {
        Instant now = Instant.parse("2026-10-15T10:00:00Z");
        return new SessionSnapshot(id, title, temp.toAbsolutePath(), now, now, List.of(), null, true);
    }

    @Test
    void createsDirectoryRoundTripsAndOverwritesInUtf8() throws IOException {
        Path directory = temp.resolve("sessions");
        JsonSessionRepository repository = new JsonSessionRepository(directory);
        assertTrue(repository.list().sessions().isEmpty());
        assertFalse(Files.exists(directory));
        SessionSnapshot original = empty(UUID.randomUUID().toString(), "会话😀");
        repository.save(original);
        assertEquals("会话😀", repository.load(original.id()).title());
        repository.save(empty(original.id(), "changed"));
        assertEquals("changed", repository.load(original.id()).title());
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
        assertFalse(repository.load(original.id()).unsaved());
    }

    @Test
    void failedReplacementPreservesOldFileAndCleansTemporaryFile() throws IOException {
        JsonSessionRepository repository = new JsonSessionRepository(temp);
        SessionSnapshot original = empty(UUID.randomUUID().toString(), "original");
        repository.save(original);
        byte[] before = Files.readAllBytes(temp.resolve(original.id() + ".json"));
        JsonSessionRepository failing = new JsonSessionRepository(temp) {
            @Override protected void moveIntoPlace(Path source, Path target) throws IOException {
                throw new IOException("simulated failure");
            }
        };
        assertEquals(SessionStorageException.Reason.IO_FAILURE, assertThrows(SessionStorageException.class,
                () -> failing.save(empty(original.id(), "changed"))).getReason());
        assertArrayEquals(before, Files.readAllBytes(temp.resolve(original.id() + ".json")));
        try (var files = Files.list(temp)) { assertEquals(1, files.count()); }
    }

    @Test
    void badFilesAreReportedWhileValidSessionsRemainSorted() throws IOException {
        JsonSessionRepository repository = new JsonSessionRepository(temp);
        String a = "00000000-0000-0000-0000-000000000001";
        String b = "00000000-0000-0000-0000-000000000002";
        repository.save(empty(b, "b"));
        repository.save(empty(a, "a"));
        Files.writeString(temp.resolve(UUID.randomUUID() + ".json"), "not json");
        Files.writeString(temp.resolve("not-an-id.json"), "{}");
        Files.writeString(temp.resolve("ignored.tmp"), "partial write");
        var catalog = repository.list();
        assertEquals(List.of(a, b), catalog.sessions().stream().map(item -> item.id()).toList());
        assertEquals(2, catalog.issues().size());
        assertEquals(2, repository.list().issues().size()); // Listing never overwrites bad files.
    }

    @Test
    void rejectsIdsAndFilesWithMismatchedIdentity() throws IOException {
        JsonSessionRepository repository = new JsonSessionRepository(temp);
        for (String id : new String[] {null, "../outside", "", "1-1-1-1-1"}) {
            assertThrows(SessionStorageException.class, () -> repository.load(id));
        }
        SessionSnapshot source = empty(UUID.randomUUID().toString(), "source");
        repository.save(source);
        String other = UUID.randomUUID().toString();
        Files.copy(temp.resolve(source.id() + ".json"), temp.resolve(other + ".json"));
        assertEquals(SessionStorageException.Reason.MALFORMED_DATA,
                assertThrows(SessionStorageException.class, () -> repository.load(other)).getReason());
        assertEquals(SessionStorageException.Reason.NOT_FOUND,
                assertThrows(SessionStorageException.class, () -> repository.load(UUID.randomUUID().toString())).getReason());
    }

    @Test
    void repositoryPathThatIsAFileReportsIoFailure() throws IOException {
        Path file = Files.writeString(temp.resolve("not-directory"), "occupied");
        JsonSessionRepository repository = new JsonSessionRepository(file);
        assertEquals(SessionStorageException.Reason.IO_FAILURE,
                assertThrows(SessionStorageException.class, repository::list).getReason());
        assertThrows(SessionStorageException.class, () -> repository.save(empty(UUID.randomUUID().toString(), "a")));
    }

    @Test
    void invalidStateCannotReplaceRestorableCheckpoint() throws IOException {
        var repository = new JsonSessionRepository(temp);
        var good = empty(UUID.randomUUID().toString(), "good");
        repository.save(good);
        byte[] before = Files.readAllBytes(temp.resolve(good.id() + ".json"));
        var bad = new SessionSnapshot(good.id(), good.title(), good.workspace(), good.createdAt(), good.updatedAt(),
                List.of(com.demo.model.Message.user("no run metadata")), null, true);
        assertEquals(SessionStorageException.Reason.MALFORMED_DATA,
                assertThrows(SessionStorageException.class, () -> repository.save(bad)).getReason());
        assertArrayEquals(before, Files.readAllBytes(temp.resolve(good.id() + ".json")));
    }
}
