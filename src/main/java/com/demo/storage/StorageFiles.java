package com.demo.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;

/** Small filesystem primitives. Reads never create directories; replacement requires atomic move. */
public class StorageFiles {
    private final Path root;

    public StorageFiles(Path root) { this.root = root.toAbsolutePath().normalize(); }
    public Path root() { return root; }

    public Path path(String relative) throws IOException {
        Path suffix = Path.of(relative);
        Path result = root.resolve(suffix).normalize();
        if (suffix.isAbsolute() || !result.startsWith(root) || result.equals(root)) {
            throw new IOException("Storage path must be a child of its root");
        }
        checkLinks(result);
        return result;
    }

    private void checkLinks(Path target) throws IOException {
        if (Files.isSymbolicLink(root)) throw new IOException("Storage root must not be a symbolic link");
        Path cursor = root;
        for (Path part : root.relativize(target)) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new IOException("Storage path must not contain symbolic links");
        }
    }

    public Optional<byte[]> read(String relative) throws IOException {
        try { return Optional.of(Files.readAllBytes(path(relative))); }
        catch (NoSuchFileException absent) { return Optional.empty(); }
    }

    public List<String> list(String relative) throws IOException {
        Path directory = path(relative);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (var children = Files.list(directory)) {
            return children.map(child -> child.getFileName().toString()).sorted().toList();
        }
    }

    private Path prepare(String relative) throws IOException {
        Path target = path(relative);
        Files.createDirectories(target.getParent());
        checkLinks(target);
        return target;
    }

    public void replace(String relative, byte[] bytes) throws IOException {
        Path target = prepare(relative);
        Path temporary = Files.createTempFile(target.getParent(), ".memory-write-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                write(channel, bytes);
                channel.force(true);
            }
            checkLinks(target);
            moveIntoPlace(temporary, target);
        } finally { Files.deleteIfExists(temporary); }
    }

    /** Fault-injection boundary; no non-atomic truncation fallback. */
    protected void moveIntoPlace(Path temporary, Path target) throws IOException {
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Caller owns the appropriate storage lock. */
    public void append(String relative, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(prepare(relative), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS)) {
            write(channel, bytes);
            channel.force(true);
        }
    }

    public void truncate(String relative, long length) throws IOException {
        Path target = path(relative);
        Path temporary = Files.createTempFile(target.getParent(), ".memory-write-", ".tmp");
        try {
            // Publish a new prefix rather than editing a file beneath concurrent journal readers.
            try (FileChannel source = FileChannel.open(target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                 FileChannel destination = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                if (length < 0 || length > source.size()) throw new IOException("Invalid journal prefix length");
                long position = 0;
                while (position < length) {
                    long copied = source.transferTo(position, Math.min(length - position, 8L * 1024 * 1024), destination);
                    if (copied == 0) throw new IOException("Journal changed during prefix copy");
                    position += copied;
                }
                destination.force(true);
            }
            checkLinks(target);
            moveIntoPlace(temporary, target);
        } finally { Files.deleteIfExists(temporary); }
    }

    public void delete(String relative) throws IOException { Files.deleteIfExists(path(relative)); }

    public Lease lock(String relative) throws IOException {
        FileChannel channel = FileChannel.open(prepare(relative), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            FileLock lock;
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException busy) { throw new BusyException(); }
            if (lock == null) throw new BusyException();
            return new Lease(channel, lock);
        } catch (IOException | RuntimeException failure) {
            channel.close();
            throw failure;
        }
    }

    private static void write(FileChannel channel, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
    }

    public static final class BusyException extends IOException {
        public BusyException() { super("Storage is busy; retry later"); }
    }

    public record Lease(FileChannel channel, FileLock lock) implements AutoCloseable {
        @Override public void close() throws IOException {
            try { lock.release(); } finally { channel.close(); }
        }
    }
}
