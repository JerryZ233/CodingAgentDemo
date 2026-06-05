package com.demo.tools;

import com.demo.model.ToolResult;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WorkspaceFileToolsSecurityTest {

    @TempDir
    Path tempDir;

    private Path workspace;

    @BeforeEach
    void setUp() throws Exception {
        workspace = Files.createDirectory(tempDir.resolve("workspace"));
    }

    @Test
    @DisplayName("Relative file paths resolve from configured workspace")
    void relativePathsResolveFromConfiguredWorkspace() throws IOException {
        ToolResult write = writeTool().execute(json("path", "nested/ok.txt", "content", "hello"));

        assertTrue(write.isSuccess(), write.getOutput());
        assertEquals("hello", Files.readString(workspace.resolve("nested/ok.txt")));
        assertFalse(Files.exists(Path.of("nested", "ok.txt").toAbsolutePath().normalize()));

        ToolResult read = readTool().execute(json("path", "nested/ok.txt"));
        assertTrue(read.isSuccess(), read.getOutput());
        assertEquals("hello", read.getOutput());

        ToolResult list = listTool().execute(json("path", "nested"));
        assertTrue(list.isSuccess(), list.getOutput());
        assertTrue(list.getOutput().contains("ok.txt"), list.getOutput());
    }

    @Test
    @DisplayName("Path traversal is rejected")
    void pathTraversalIsRejected() {
        ToolResult read = readTool().execute(json("path", "../outside.txt"));
        ToolResult write = writeTool().execute(json("path", "../outside.txt", "content", "nope"));
        ToolResult list = listTool().execute(json("path", ".."));

        assertFalse(read.isSuccess());
        assertTrue(read.getOutput().contains("Path traversal"), read.getOutput());
        assertFalse(write.isSuccess());
        assertTrue(write.getOutput().contains("Path traversal"), write.getOutput());
        assertFalse(list.isSuccess());
        assertTrue(list.getOutput().contains("Path traversal"), list.getOutput());
    }

    @Test
    @DisplayName("Absolute sibling path is rejected")
    void absoluteSiblingPathIsRejected() throws IOException {
        Path sibling = Files.createDirectory(tempDir.resolve("workspace-sibling"));
        Path siblingFile = Files.writeString(sibling.resolve("file.txt"), "outside");

        ToolResult read = readTool().execute(json("path", siblingFile.toString()));
        ToolResult write = writeTool().execute(json("path", sibling.resolve("new.txt").toString(), "content", "nope"));
        ToolResult list = listTool().execute(json("path", sibling.toString()));

        assertFalse(read.isSuccess());
        assertTrue(read.getOutput().contains("outside workspace"), read.getOutput());
        assertFalse(write.isSuccess());
        assertTrue(write.getOutput().contains("outside workspace"), write.getOutput());
        assertFalse(list.isSuccess());
        assertTrue(list.getOutput().contains("outside workspace"), list.getOutput());
    }

    @Test
    @DisplayName("Read and list reject symlinks that escape workspace")
    void readAndListRejectEscapingSymlinks() throws IOException {
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Files.writeString(outside.resolve("note.txt"), "outside");
        assumeTrue(createSymlink(workspace.resolve("outside-link"), outside));

        ToolResult read = readTool().execute(json("path", "outside-link/note.txt"));
        ToolResult list = listTool().execute(json("path", "outside-link"));

        assertFalse(read.isSuccess());
        assertTrue(read.getOutput().contains("outside workspace"), read.getOutput());
        assertFalse(list.isSuccess());
        assertTrue(list.getOutput().contains("outside workspace"), list.getOutput());
    }

    @Test
    @DisplayName("Write rejects symlink file that escapes workspace")
    void writeRejectsEscapingSymlinkFile() throws IOException {
        Path outside = Files.writeString(tempDir.resolve("outside.txt"), "outside");
        assumeTrue(createSymlink(workspace.resolve("link.txt"), outside));

        ToolResult result = writeTool().execute(json("path", "link.txt", "content", "changed"));

        assertFalse(result.isSuccess());
        assertTrue(result.getOutput().contains("outside workspace"), result.getOutput());
        assertEquals("outside", Files.readString(outside));
    }

    private static boolean createSymlink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (UnsupportedOperationException | SecurityException e) {
            return false;
        } catch (IOException e) {
            if (System.getProperty("os.name").toLowerCase().contains("windows")) {
                return false;
            }
            throw e;
        }
    }

    private FileReadTool readTool() {
        return new FileReadTool(workspace);
    }

    private FileWriteTool writeTool() {
        return new FileWriteTool(workspace);
    }

    private FileListTool listTool() {
        return new FileListTool(workspace);
    }

    private static String json(String key, String value) {
        JsonObject object = new JsonObject();
        object.addProperty(key, value);
        return object.toString();
    }

    private static String json(String key1, String value1, String key2, String value2) {
        JsonObject object = new JsonObject();
        object.addProperty(key1, value1);
        object.addProperty(key2, value2);
        return object.toString();
    }
}
