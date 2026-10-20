package com.demo.memory;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ProjectLocatorTest {
    @TempDir Path temp;
    private final ProjectLocator locator = new ProjectLocator();

    @Test void siblingWorkspaceDirectoriesShareRepositoryMemoryWithoutChangingWorkspace() throws Exception {
        Path root = Files.createDirectory(temp.resolve("repository"));
        Files.createDirectory(root.resolve(".git"));
        Path first = Files.createDirectories(root.resolve("a/src"));
        Path second = Files.createDirectories(root.resolve("b/src"));
        var project = locator.locate(first);
        assertEquals(root.toRealPath(), project.root());
        assertEquals(first.toRealPath(), project.workspace());
        assertEquals(project.memoryDirectory(), locator.locate(second).memoryDirectory());
        assertFalse(Files.exists(project.memoryDirectory()));
    }

    @Test void gitFileUsesWorktreeRootRatherThanSharedGitDirectory() throws Exception {
        Path first = Files.createDirectory(temp.resolve("worktree-one"));
        Path second = Files.createDirectory(temp.resolve("worktree-two"));
        for (Path root : java.util.List.of(first, second)) {
            Files.writeString(root.resolve(".git"), "gitdir: ../shared/.git/worktrees/example\n");
            Files.createDirectory(root.resolve("src"));
        }
        assertEquals(first.toRealPath(), locator.locate(first.resolve("src")).root());
        assertNotEquals(locator.locate(first).memoryDirectory(), locator.locate(second).memoryDirectory());
    }

    @Test void nestedRepositoryUsesNearestMarkerAndMalformedMarkerDoesNotFallBack() throws Exception {
        Files.createDirectory(temp.resolve(".git"));
        Path nested = Files.createDirectory(temp.resolve("nested"));
        Files.createDirectory(nested.resolve(".git"));
        assertEquals(nested.toRealPath(), locator.locate(nested).root());
        Path malformed = Files.createDirectory(temp.resolve("malformed"));
        Files.writeString(malformed.resolve(".git"), "unexpected marker");
        assertThrows(java.io.IOException.class, () -> locator.locate(malformed));
    }

    @Test void rejectsMissingOrNonDirectoryWorkspaces() throws Exception {
        assertThrows(java.io.IOException.class, () -> locator.locate(temp.resolve("missing")));
        Path file = Files.writeString(temp.resolve("file.txt"), "text");
        assertThrows(java.io.IOException.class, () -> locator.locate(file));
    }
}
