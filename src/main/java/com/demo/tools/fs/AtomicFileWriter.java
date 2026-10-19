package com.demo.tools.fs;

import com.demo.tools.support.ToolLimits;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Serializes agent writes; hashes detect external edits but are not an OS compare-and-swap. */
public final class AtomicFileWriter {
    private final WorkspaceFileAccess access;
    private final Replacement replacement;
    public AtomicFileWriter(WorkspaceFileAccess access) { this(access, AtomicFileWriter::replace); }
    AtomicFileWriter(WorkspaceFileAccess access, Replacement replacement) { this.access = access; this.replacement = replacement; }
    public Result replace(String input, String oldText, String newText, String expected) throws IOException {
        if (expected == null || !expected.matches("[0-9a-fA-F]{64}")
                || oldText == null || oldText.isEmpty() || newText == null || oldText.equals(newText))
            throw new IllegalArgumentException("Invalid replacement arguments");
        synchronized (access.writeLock()) {
            Path target = access.writable(input);
            WorkspaceFileAccess.Snapshot snapshot = access.read(input);
            if (!snapshot.sha256().equalsIgnoreCase(expected)) throw conflict();
            String text = WorkspaceFileAccess.decode(snapshot.bytes());
            int offset = text.indexOf(oldText);
            if (offset < 0) throw new FileAccessException("TEXT_NOT_FOUND", "Original text was not found");
            if (text.indexOf(oldText, offset + 1) >= 0)
                throw new FileAccessException("TEXT_NOT_UNIQUE", "Original text matches more than once; provide more context");
            // write() uses the same reentrant workspace lock and repeats path/hash checks before commit.
            return write(access.relative(target), text.substring(0, offset) + newText + text.substring(offset + oldText.length()), expected);
        }
    }
    public Result write(String input, String content, String expected) throws IOException {
        if (expected != null && !expected.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException("Invalid SHA-256");
        byte[] bytes;
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(content));
            bytes = new byte[encoded.remaining()]; encoded.get(bytes);
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new FileAccessException("UNSUPPORTED_ENCODING", "Content contains invalid Unicode");
        }
        if (bytes.length > ToolLimits.FILE_BYTES) throw new FileAccessException("FILE_TOO_LARGE", "Content exceeds 1 MiB");
        WorkspaceFileAccess.decode(bytes);
        synchronized (access.writeLock()) {
            Path target = access.writable(input);
            boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
            check(target, expected);
            access.createParents(target);
            Path temporary = Files.createTempFile(target.getParent(), ".agent-write-", ".tmp");
            try {
                if (exists) preservePermissions(target, temporary);
                try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) channel.write(buffer);
                    channel.force(true);
                }
                access.writable(input);
                check(target, expected);
                if (exists) {
                    try { replacement.replace(temporary, target); }
                    catch (AtomicMoveNotSupportedException e) {
                        throw new FileAccessException("ATOMIC_REPLACE_UNSUPPORTED", "Atomic replacement is required");
                    }
                } else {
                    // A hard-link publication is atomic CREATE_NEW, unlike ATOMIC_MOVE which may replace.
                    try { Files.createLink(target, temporary); }
                    catch (FileAlreadyExistsException e) { throw conflict(); }
                    catch (UnsupportedOperationException e) {
                        throw new FileAccessException("ATOMIC_CREATE_UNSUPPORTED", "Safe new-file publication is unavailable");
                    }
                }
                return new Result(access.relative(target), exists ? "overwritten" : "created", bytes.length,
                        WorkspaceFileAccess.sha256(bytes));
            } finally { Files.deleteIfExists(temporary); }
        }
    }
    // Separate commit operation permits deterministic provider-failure tests without weakening production policy.
    static void replace(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    private void check(Path path, String expected) throws IOException {
        boolean exists = Files.exists(path, LinkOption.NOFOLLOW_LINKS);
        if (exists != (expected != null)) throw conflict();
        if (exists) {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw conflict();
            if (!WorkspaceFileAccess.sha256(access.readBytes(path)).equalsIgnoreCase(expected)) throw conflict();
        }
    }
    private static void preservePermissions(Path source, Path destination) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(source, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            PosixFileAttributes attrs = posix.readAttributes();
            PosixFileAttributeView dest = Files.getFileAttributeView(destination, PosixFileAttributeView.class);
            dest.setOwner(attrs.owner()); dest.setGroup(attrs.group()); dest.setPermissions(attrs.permissions());
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(source, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl != null) {
            AclFileAttributeView dest = Files.getFileAttributeView(destination, AclFileAttributeView.class);
            dest.setOwner(acl.getOwner()); dest.setAcl(acl.getAcl());
            DosFileAttributeView dos = Files.getFileAttributeView(source, DosFileAttributeView.class);
            if (dos != null && dos.readAttributes().isReadOnly())
                throw new FileAccessException("ACCESS_DENIED", "Read-only target cannot be overwritten");
            return;
        }
        throw new FileAccessException("PERMISSIONS_UNSUPPORTED", "Cannot preserve file permissions");
    }
    private static FileAccessException conflict() { return new FileAccessException("WRITE_CONFLICT", "Target changed; read it again before editing (complete content is required for overwrite)"); }
    public record Result(String path, String action, int bytes, String sha256) { }
    @FunctionalInterface interface Replacement { void replace(Path source, Path target) throws IOException; }
}
