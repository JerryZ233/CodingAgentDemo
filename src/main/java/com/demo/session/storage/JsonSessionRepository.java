package com.demo.session.storage;

import com.demo.session.SessionCatalog;
import com.demo.session.SessionSnapshot;
import com.demo.session.SessionSummary;
import com.demo.storage.StorageFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Versioned per-session files. Atomic checkpoints, but not filesystem-operation transactions. */
public class JsonSessionRepository implements SessionRepository {
    private final Path directory;
    private final SessionDocumentCodec codec = new SessionDocumentCodec();

    public JsonSessionRepository(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
    }

    public Path directory() { return directory; }

    public static Path defaultDirectory() {
        return Path.of(System.getProperty("user.home"), ".coding-agent", "sessions");
    }

    @Override
    public void save(SessionSnapshot snapshot) {
        pathFor(snapshot.id());
        try (var lock = new StorageFiles(directory).lock(".locks/" + snapshot.id() + ".lck")) {
            saveLocked(snapshot);
        } catch (StorageFiles.BusyException busy) {
            throw new SessionStorageException(SessionStorageException.Reason.CONFLICT, "Session checkpoint is busy", busy);
        } catch (IOException | SecurityException failure) { throw ioFailure("lock", snapshot.id(), failure); }
    }

    @Override
    public void compareAndSave(SessionSnapshot expected, SessionSnapshot replacement) {
        if (!expected.id().equals(replacement.id())) throw new IllegalArgumentException("Session identity changed");
        pathFor(expected.id());
        try (var lock = new StorageFiles(directory).lock(".locks/" + expected.id() + ".lck")) {
            if (!load(expected.id()).samePersistedState(expected)) {
                throw new SessionStorageException(SessionStorageException.Reason.CONFLICT, "Session changed before archive commit");
            }
            saveLocked(replacement);
        } catch (StorageFiles.BusyException busy) {
            throw new SessionStorageException(SessionStorageException.Reason.CONFLICT, "Session checkpoint is busy", busy);
        } catch (IOException | SecurityException failure) { throw ioFailure("conditional save", expected.id(), failure); }
    }

    private void saveLocked(SessionSnapshot snapshot) {
        Path target = pathFor(snapshot.id());
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            if (Files.isSymbolicLink(target)) throw new IOException("Symbolic link target");
            String document = codec.encode(snapshot);
            temporary = Files.createTempFile(directory, snapshot.id() + "-", ".tmp");
            try (var channel = java.nio.channels.FileChannel.open(temporary, java.nio.file.StandardOpenOption.WRITE)) {
                var buffer = java.nio.ByteBuffer.wrap(document.getBytes(StandardCharsets.UTF_8));
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            moveIntoPlace(temporary, target);
        } catch (IOException | SecurityException e) {
            throw ioFailure("save", snapshot.id(), e);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException | SecurityException ignored) { }
            }
        }
    }

    /** Separated for deterministic write-failure tests. */
    protected void moveIntoPlace(Path temporary, Path target) throws IOException {
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    public SessionSnapshot load(String id) {
        Path target = pathFor(id);
        try {
            if (Files.isSymbolicLink(target)) {
                throw new SessionStorageException(SessionStorageException.Reason.MALFORMED_DATA,
                        "Session file must not be a symbolic link: " + id);
            }
            SessionSnapshot snapshot = codec.decode(Files.readString(target, StandardCharsets.UTF_8));
            if (!snapshot.id().equals(id)) {
                throw new SessionStorageException(SessionStorageException.Reason.MALFORMED_DATA,
                        "Session ID does not match file name: " + id);
            }
            return snapshot;
        } catch (NoSuchFileException e) {
            throw new SessionStorageException(SessionStorageException.Reason.NOT_FOUND,
                    "Session not found: " + id, e);
        } catch (SessionStorageException e) {
            throw new SessionStorageException(e.getReason(), "Cannot load session " + id + ": " + e.getMessage(), e);
        } catch (IOException | SecurityException e) {
            throw ioFailure("load", id, e);
        }
    }

    @Override
    public SessionCatalog list() {
        List<SessionSummary> sessions = new ArrayList<>();
        List<SessionCatalog.LoadIssue> issues = new ArrayList<>();
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return new SessionCatalog(sessions, issues);
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json")).sorted().toList()) {
                String name = file.getFileName().toString();
                try {
                    sessions.add(SessionSummary.from(load(name.substring(0, name.length() - 5))));
                } catch (SessionStorageException e) {
                    issues.add(new SessionCatalog.LoadIssue(name, e.getReason(), e.getMessage()));
                }
            }
            return new SessionCatalog(sessions, issues);
        } catch (IOException | SecurityException e) {
            throw ioFailure("list", "repository", e);
        }
    }

    private Path pathFor(String id) {
        try {
            SessionSnapshot.requireId(id);
        } catch (IllegalArgumentException e) {
            throw new SessionStorageException(SessionStorageException.Reason.MALFORMED_DATA,
                    "Session ID must be a canonical UUID", e);
        }
        return directory.resolve(id + ".json");
    }

    private SessionStorageException ioFailure(String operation, String id, Exception cause) {
        return new SessionStorageException(SessionStorageException.Reason.IO_FAILURE,
                "Failed to " + operation + " session " + id, cause);
    }
}
