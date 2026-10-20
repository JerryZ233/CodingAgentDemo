package com.demo.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StorageFilesTest {
    @TempDir Path temp;

    @Test void readDoesNotInitializeAndPathsStayInsideRoot() throws Exception {
        Path root = temp.resolve("new");
        var files = new StorageFiles(root);
        assertTrue(files.read("MEMORY.md").isEmpty());
        assertTrue(files.list("local/transactions").isEmpty());
        assertFalse(Files.exists(root));
        assertThrows(IOException.class, () -> files.path("../outside"));
        assertThrows(IOException.class, () -> files.path(temp.resolve("outside").toAbsolutePath().toString()));
    }

    @Test void failedAtomicReplacePreservesPreviousFileAndRemovesTemporaryFile() throws Exception {
        Files.writeString(temp.resolve("MEMORY.md"), "original");
        var files = new StorageFiles(temp) {
            @Override protected void moveIntoPlace(Path source, Path target) throws IOException {
                throw new java.nio.file.AtomicMoveNotSupportedException(source.toString(), target.toString(), "injected");
            }
        };
        assertThrows(IOException.class, () -> files.replace("MEMORY.md", new byte[] {1, 2}));
        assertEquals("original", Files.readString(temp.resolve("MEMORY.md")));
        try (var children = Files.list(temp)) { assertEquals(1, children.count()); }
    }

    @Test void locksRejectAnotherInstanceAndReleaseAfterFailure() throws Exception {
        var first = new StorageFiles(temp);
        var second = new StorageFiles(temp);
        try (var lock = first.lock("local/locks/memory.lck")) {
            assertThrows(StorageFiles.BusyException.class, () -> second.lock("local/locks/memory.lck"));
        }
        try (var lock = second.lock("local/locks/memory.lck")) { assertTrue(lock.lock().isValid()); }
    }

    @Test void journalPrefixRepairIsAtomicAndFailureKeepsOriginalBytes() throws Exception {
        Files.writeString(temp.resolve("journal"), "committed\npartial");
        var failing = new StorageFiles(temp) {
            @Override protected void moveIntoPlace(Path source, Path target) throws IOException {
                throw new IOException("injected replacement failure");
            }
        };
        assertThrows(IOException.class, () -> failing.truncate("journal", 10));
        assertEquals("committed\npartial", Files.readString(temp.resolve("journal")));
        new StorageFiles(temp).truncate("journal", 10);
        assertEquals("committed\n", Files.readString(temp.resolve("journal")));
        try (var children = Files.list(temp)) { assertEquals(1, children.count()); }
    }

    @Test void anotherJavaProcessObservesTheSameFileLock() throws Exception {
        var files = new StorageFiles(temp);
        try (var lock = files.lock("local/locks/memory.lck")) { assertEquals("BUSY", probe()); }
        assertEquals("ACQUIRED", probe());
    }

    private String probe() throws Exception {
        String testClasses = Path.of(StorageLockProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String mainClasses = Path.of(StorageFiles.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        Process process = new ProcessBuilder(javaExecutable, "-cp", testClasses + java.io.File.pathSeparator + mainClasses,
                StorageLockProbe.class.getName(), temp.toString()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS));
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
            assertEquals(0, process.exitValue(), output);
            return output;
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}
