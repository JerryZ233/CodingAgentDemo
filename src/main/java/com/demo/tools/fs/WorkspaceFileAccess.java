package com.demo.tools.fs;

import com.demo.tools.support.ToolLimits;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;

/** A fixed real workspace binding; never silently follows a replaced workspace root. */
public final class WorkspaceFileAccess {
    private static final Object[] LOCKS = new Object[64];
    static { Arrays.setAll(LOCKS, i -> new Object()); }
    private final Path requestedRoot;
    private final Path root;
    private final Object rootKey;
    private final java.nio.file.attribute.FileTime rootCreated;
    private final FileAccessPolicy policy = new FileAccessPolicy();
    public WorkspaceFileAccess(Path workspace) {
        try {
            requestedRoot = workspace.toAbsolutePath().normalize();
            root = requestedRoot.toRealPath();
            BasicFileAttributes attrs = Files.readAttributes(root, BasicFileAttributes.class);
            if (!attrs.isDirectory()) throw new IOException("not a directory");
            rootKey = attrs.fileKey();
            rootCreated = attrs.creationTime();
        } catch (IOException e) { throw new IllegalArgumentException("Workspace must be an existing directory", e); }
    }
    public Path root() { return root; }
    public Object writeLock() { return LOCKS[Math.floorMod(root.hashCode(), LOCKS.length)]; }
    public void verifyBinding() throws IOException {
        BasicFileAttributes current = Files.readAttributes(root, BasicFileAttributes.class);
        if (!requestedRoot.toRealPath().equals(root)
                || !Objects.equals(rootKey, current.fileKey()) || !rootCreated.equals(current.creationTime()))
            throw new FileAccessException("WORKSPACE_CHANGED", "Workspace binding changed");
    }
    public Path lexical(String input, boolean write) throws IOException {
        verifyBinding();
        if (input == null || input.isBlank() || input.length() > ToolLimits.PATH_CHARS)
            throw new IllegalArgumentException("Invalid path length");
        Path supplied = Path.of(input);
        Path path = (supplied.isAbsolute() ? supplied : root.resolve(supplied)).normalize();
        if (!path.startsWith(root) || !policy.allowedAbsolute(path)) throw denied();
        // Inspect both original path segments and normalized path (including Windows stream/device syntax).
        for (Path segment : supplied) {
            String name = segment.toString();
            if (name.contains(":")) throw denied();
            if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows")
                    && (name.endsWith(".") && !name.equals(".") && !name.equals("..") || name.endsWith(" ")))
                throw denied();
        }
        if (!policy.allowed(supplied, write) || !policy.allowed(path, write)) throw denied();
        return path;
    }
    public Path existing(String input) throws IOException {
        Path path = lexical(input, false);
        Path real = path.toRealPath();
        if (!real.startsWith(root) || !policy.allowedAbsolute(real) || !policy.allowed(real, false)) throw denied();
        return real;
    }
    /** Search never follows links, including links in an explicitly supplied query root. */
    public Path existingNoLinks(String input) throws IOException {
        Path path = lexical(input, false);
        Path current = root;
        for (Path part : root.relativize(path)) {
            current = current.resolve(part);
            BasicFileAttributes attrs = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isSymbolicLink() || attrs.isOther() || !current.toRealPath().equals(current)) throw denied();
        }
        return existing(input);
    }
    public Path writable(String input) throws IOException {
        Path path = lexical(input, true);
        if (path.equals(root)) throw denied();
        Path current = root;
        for (Path part : root.relativize(path)) {
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes a = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (a.isSymbolicLink() || a.isOther() || !current.toRealPath().equals(current)) throw denied();
                if (!policy.allowed(current.toRealPath(), true)) throw denied();
            }
        }
        return path;
    }
    public void createParents(Path target) throws IOException {
        Path current = root;
        for (Path part : root.relativize(target.getParent())) {
            current = current.resolve(part);
            writable(relative(current.resolve(".parent-check")));
            try { Files.createDirectory(current); } catch (FileAlreadyExistsException ignored) { }
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) throw denied();
            writable(relative(current.resolve(".parent-check")));
        }
    }
    public boolean visible(Path path, boolean link) throws IOException {
        if (!policy.allowedAbsolute(path) || !policy.allowed(path, false)) return false;
        if (link) {
            try { existing(relative(path)); } catch (IOException | SecurityException e) { return false; }
        }
        return true;
    }
    public String relative(Path path) {
        String relative = root.relativize(path).toString().replace('\\', '/');
        return relative.isEmpty() ? "." : relative;
    }
    public Snapshot read(String input) throws IOException {
        return read(input, ToolLimits.FILE_BYTES);
    }
    Snapshot read(String input, int maxBytes) throws IOException {
        Path path = existing(input);
        BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile()) throw new FileAccessException("NOT_A_FILE", "Expected a regular file");
        byte[] bytes = readBytes(path, maxBytes);
        BasicFileAttributes after = Files.readAttributes(existing(input), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!same(before, after)) throw new FileAccessException("FILE_CHANGED", "File changed during reading");
        return new Snapshot(relative(path), bytes, sha256(bytes));
    }
    byte[] readBytes(Path path) throws IOException {
        return readBytes(path, ToolLimits.FILE_BYTES);
    }
    private byte[] readBytes(Path path, int maxBytes) throws IOException {
        verifyBinding();
        // Use a directory-relative no-follow open on providers supporting secure directory streams.
        try (DirectoryStream<Path> directory = Files.newDirectoryStream(path.getParent())) {
            Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            try (SeekableByteChannel channel = directory instanceof SecureDirectoryStream<Path> secure
                    ? secure.newByteChannel(path.getFileName(), options) : Files.newByteChannel(path, options)) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                ByteBuffer buffer = ByteBuffer.allocate(8192);
                while (channel.read(buffer) != -1) {
                    buffer.flip();
                    if (bytes.size() + buffer.remaining() > maxBytes)
                        throw new FileAccessException("FILE_TOO_LARGE", "File exceeds the read byte limit");
                    bytes.write(buffer.array(), 0, buffer.remaining()); buffer.clear();
                }
                return bytes.toByteArray();
            }
        }
    }
    public static String decode(byte[] bytes) throws IOException {
        for (byte b : bytes) if (b == 0) throw new FileAccessException("UNSUPPORTED_CONTENT", "Binary content is not supported");
        try { return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException e) { throw new FileAccessException("UNSUPPORTED_ENCODING", "Expected valid UTF-8 text"); }
    }
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    static boolean same(BasicFileAttributes a, BasicFileAttributes b) {
        return Objects.equals(a.fileKey(), b.fileKey()) && a.size() == b.size()
                && a.lastModifiedTime().equals(b.lastModifiedTime()) && a.creationTime().equals(b.creationTime());
    }
    private static FileAccessException denied() { return new FileAccessException("ACCESS_DENIED", "Path is outside workspace or protected"); }
    public record Snapshot(String path, byte[] bytes, String sha256) { }
}
