package com.demo.tools.fs;

import com.demo.tools.builtin.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FileAccessTest {
    @TempDir Path temp;
    @Test void selectingSensitiveDirectoryDoesNotBypassSensitivePolicy() throws Exception {
        Path secrets = Files.createDirectory(temp.resolve("secrets"));
        Files.writeString(secrets.resolve("ordinary.txt"), "sensitive");
        var access = new WorkspaceFileAccess(secrets);
        assertEquals("ACCESS_DENIED", assertThrows(FileAccessException.class, () -> access.read("ordinary.txt")).code());
        var policy = new FileAccessPolicy();
        assertFalse(policy.allowedAbsolute(temp.resolve("Windows/System32/config")));
        assertTrue(policy.allowedAbsolute(temp.resolve("src/usr/module")));
    }
    @Test void replacementFailureLeavesOriginalAndNoTemporaryFiles() throws Exception {
        Path target = Files.writeString(temp.resolve("a"), "old");
        var access = new WorkspaceFileAccess(temp);
        String sha = access.read("a").sha256();
        for (IOException failure : List.of(new AtomicMoveNotSupportedException("", "", "test"), new IOException("disk failure"))) {
            var writer = new AtomicFileWriter(access, (source, destination) -> { throw failure; });
            assertThrows(IOException.class, () -> writer.write("a", "new", sha));
            assertThrows(IOException.class, () -> writer.replace("a", "old", "new", sha));
            assertEquals("old", Files.readString(target));
            try (var files = Files.list(temp)) { assertEquals(1, files.count()); }
        }
    }
    @Test void preservesPermissionsAcrossActualReplacement() throws Exception {
        Path file = Files.writeString(temp.resolve("a"), "old");
        var posix = Files.getFileAttributeView(file, java.nio.file.attribute.PosixFileAttributeView.class);
        var acl = Files.getFileAttributeView(file, java.nio.file.attribute.AclFileAttributeView.class);
        Object before = posix != null ? posix.readAttributes().permissions() : acl.getAcl();
        var access = new WorkspaceFileAccess(temp);
        new AtomicFileWriter(access).write("a", "new", access.read("a").sha256());
        Object after = posix != null ? Files.getPosixFilePermissions(file)
                : Files.getFileAttributeView(file, java.nio.file.attribute.AclFileAttributeView.class).getAcl();
        assertEquals(before, after);
    }
    @Test void scanLimitsAndTimeoutsDoNotReturnPartialSuccess() throws Exception {
        Files.createFile(temp.resolve("a")); Files.createFile(temp.resolve("b"));
        var access = new WorkspaceFileAccess(temp);
        assertEquals("DIRECTORY_SCAN_LIMIT", assertThrows(FileAccessException.class,
                () -> new DirectoryListing(access, 1, Duration.ofSeconds(5)).list(".", 100, null, null, false, 10)).code());
        assertEquals("DIRECTORY_SCAN_LIMIT", assertThrows(FileAccessException.class,
                () -> new DirectoryListing(access, 100, Duration.ofNanos(1)).list(".", 100, null, null, false, 10)).code());
    }
    @Test void replacedWorkspaceIsNotSilentlyRebound() throws Exception {
        Path root = Files.createDirectory(temp.resolve("root"));
        var access = new WorkspaceFileAccess(root);
        Files.move(root, temp.resolve("old-root")); Files.createDirectory(root);
        assertEquals("WORKSPACE_CHANGED", assertThrows(FileAccessException.class, access::verifyBinding).code());
    }
    @Test void inWorkspaceSensitiveAliasIsDeniedAndSafeLinksCannotBeWritten() throws Exception {
        Files.writeString(temp.resolve("config.local.yaml"), "sensitive");
        Files.writeString(temp.resolve("safe.txt"), "safe");
        try {
            Files.createSymbolicLink(temp.resolve("alias.txt"), temp.resolve("config.local.yaml"));
            Files.createSymbolicLink(temp.resolve("safe-alias.txt"), temp.resolve("safe.txt"));
        } catch (IOException | UnsupportedOperationException | SecurityException e) { assumeTrue(false, "Symlink creation unavailable: " + e.getClass().getSimpleName()); }
        var access = new WorkspaceFileAccess(temp);
        assertEquals("ACCESS_DENIED", assertThrows(FileAccessException.class, () -> access.read("alias.txt")).code());
        assertEquals("safe", WorkspaceFileAccess.decode(access.read("safe-alias.txt").bytes()));
        assertEquals("ACCESS_DENIED", assertThrows(FileAccessException.class, () -> access.writable("safe-alias.txt")).code());
        var listed = com.google.gson.JsonParser.parseString(new FileListTool(temp).execute("{\"path\":\".\"}").getOutput()).getAsJsonObject().getAsJsonArray("entries");
        assertTrue(java.util.stream.StreamSupport.stream(listed.spliterator(), false)
                .noneMatch(e -> e.getAsJsonObject().get("name").getAsString().equals("alias.txt")));
    }
    @Test void windowsJunctionCannotEscapeOrBecomeAWriteAncestor() throws Exception {
        assumeTrue(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows"));
        Path root = Files.createDirectory(temp.resolve("root"));
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Path link = root.resolve("junction");
        String cmd = Path.of(System.getenv("SystemRoot"), "System32", "cmd.exe").toString();
        Process process = new ProcessBuilder(cmd, "/d", "/c", "mklink", "/J", link.toString(), outside.toString()).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assumeTrue(process.waitFor() == 0, "Junction creation unavailable");
        try {
            var access = new WorkspaceFileAccess(root);
            assertEquals("ACCESS_DENIED", assertThrows(FileAccessException.class, () -> access.existing("junction")).code());
            assertEquals("ACCESS_DENIED", assertThrows(FileAccessException.class, () -> access.writable("junction/a.txt")).code());
        } finally { Files.deleteIfExists(link); }
    }
}
