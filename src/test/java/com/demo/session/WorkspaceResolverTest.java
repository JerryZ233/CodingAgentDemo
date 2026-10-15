package com.demo.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WorkspaceResolverTest {
    @TempDir Path temp;
    private final WorkspaceResolver resolver = new WorkspaceResolver();

    @Test
    void resolvesRelativeDirectoryWithoutChangingProcessWorkingDirectory() throws IOException {
        String before = System.getProperty("user.dir");
        assertEquals(Path.of(".").toRealPath(), resolver.resolve(Path.of(".")));
        assertEquals(temp.toRealPath(), resolver.resolve(temp.resolve(".")));
        assertEquals(before, System.getProperty("user.dir"));
    }

    @Test
    void rejectsMissingDirectoryFilesNullAndDeletedWorkspaces() throws IOException {
        for (Path invalid : new Path[] {null, temp.resolve("missing"), Files.writeString(temp.resolve("a.txt"), "a")}) {
            assertEquals(SessionException.Reason.INVALID_WORKSPACE,
                    assertThrows(SessionException.class, () -> resolver.resolve(invalid)).getReason());
        }
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path stored = resolver.resolve(workspace);
        Files.delete(workspace);
        assertThrows(SessionException.class, () -> resolver.validateStored(stored));
    }

    @Test
    void detectsDirectoryReplacedBySymlinkToDifferentLocation() throws IOException {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path stored = resolver.resolve(workspace);
        Path other = Files.createDirectory(temp.resolve("other"));
        Files.delete(workspace);
        try { Files.createSymbolicLink(workspace, other); }
        catch (IOException | UnsupportedOperationException | SecurityException e) {
            assumeTrue(false, "Symlink creation unavailable: " + e.getClass().getSimpleName());
        }
        assertThrows(SessionException.class, () -> resolver.validateStored(stored));
        assertEquals(other.toRealPath(), resolver.resolve(workspace));
    }
}
