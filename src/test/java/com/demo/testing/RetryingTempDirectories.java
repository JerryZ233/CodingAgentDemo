package com.demo.testing;

import org.junit.jupiter.api.extension.*;
import org.junit.jupiter.api.io.TempDirFactory;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Windows can acknowledge unlink before scanner-held handles allow deleting the parent directory. */
public final class RetryingTempDirectories implements TempDirFactory, AfterEachCallback, AfterAllCallback {
    private static final Map<String, List<Path>> ROOTS = new ConcurrentHashMap<>();
    @Override public Path createTempDirectory(AnnotatedElementContext element, ExtensionContext context) throws IOException {
        Path base = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        Path directory = Files.createTempDirectory(base, "junit");
        ROOTS.computeIfAbsent(context.getUniqueId(), key -> Collections.synchronizedList(new ArrayList<>())).add(directory);
        return directory;
    }
    @Override public void afterEach(ExtensionContext context) throws Exception { cleanup(context); }
    @Override public void afterAll(ExtensionContext context) throws Exception { cleanup(context); }
    private void cleanup(ExtensionContext context) throws Exception {
        List<Path> directories = ROOTS.remove(context.getUniqueId());
        if (directories == null) return;
        Path base = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        for (Path directory : directories) {
            Path target = directory.toAbsolutePath().normalize();
            if (!target.startsWith(base) || target.equals(base) || !target.getParent().equals(base)
                    || !target.getFileName().toString().startsWith("junit"))
                throw new IOException("Refusing cleanup outside the test temporary directory");
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (true) {
                try { remove(target); break; }
                catch (NoSuchFileException ignored) { break; }
                catch (DirectoryNotEmptyException | AccessDeniedException e) {
                    if (System.nanoTime() >= deadline) throw e;
                    Thread.sleep(20);
                }
            }
        }
    }
    private void remove(Path target) throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return;
        BasicFileAttributes attrs = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attrs.isSymbolicLink() || attrs.isOther() || !target.toRealPath().equals(target)) { Files.delete(target); return; }
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (attributes.isOther() || !directory.toRealPath().equals(directory)) {
                    Files.delete(directory); return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                if (error != null) throw error;
                Files.deleteIfExists(directory); return FileVisitResult.CONTINUE;
            }
        });
    }
}
