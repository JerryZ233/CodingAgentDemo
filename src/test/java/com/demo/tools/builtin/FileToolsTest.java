package com.demo.tools.builtin;

import com.demo.model.ToolResult;
import com.demo.tools.fs.WorkspaceFileAccess;
import com.demo.tools.support.ToolLimits;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class FileToolsTest {
    @TempDir Path root;
    private JsonObject data(ToolResult result) { return JsonParser.parseString(result.getOutput()).getAsJsonObject(); }
    private String args(String... fields) {
        JsonObject json = new JsonObject();
        for (int i = 0; i < fields.length; i += 2) json.addProperty(fields[i], fields[i + 1]);
        return json.toString();
    }
    private ToolResult write(String path, String text) { return new FileWriteTool(root).execute(args("path", path, "content", text)); }
    private ToolResult read(String path) { return new FileReadTool(root).execute(args("path", path)); }
    private void code(String code, ToolResult result) { assertFalse(result.isSuccess()); assertEquals(code, data(result).get("code").getAsString()); }

    @Test void missingNullUnknownAndWrongTypeNeverMutate() throws Exception {
        Files.writeString(root.resolve("a.txt"), "original");
        for (String json : List.of("{\"path\":\"a.txt\"}", "{\"path\":\"a.txt\",\"content\":null}",
                "{\"path\":\"a.txt\",\"content\":false}", "{\"path\":\"a.txt\",\"content\":\"\",\"extra\":1}")) {
            code("INVALID_ARGUMENTS", new FileWriteTool(root).execute(json));
            assertEquals("original", Files.readString(root.resolve("a.txt")));
        }
        try (var files = Files.list(root)) { assertEquals(1, files.count()); }
    }
    @Test void completeReadAndHashProtectedOverwriteIncludingEmptyText() throws Exception {
        assertTrue(write("a..b.txt", "你好\nhello").isSuccess());
        ToolResult read = read("nested/../a..b.txt"); // normalization does not require nested to exist
        assertTrue(read.isSuccess(), read.getOutput());
        JsonObject file = data(read);
        assertEquals("你好\nhello", file.get("content").getAsString());
        assertEquals("a..b.txt", file.get("path").getAsString());
        assertEquals(WorkspaceFileAccess.sha256("你好\nhello".getBytes(StandardCharsets.UTF_8)), file.get("sha256").getAsString());
        code("WRITE_CONFLICT", write("a..b.txt", "bad"));
        var cleared = new FileWriteTool(root).execute(args("path", "a..b.txt", "content", "", "expected_sha256", file.get("sha256").getAsString()));
        assertTrue(cleared.isSuccess(), cleared.getOutput());
        assertEquals(0, Files.size(root.resolve("a..b.txt")));
        assertEquals("overwritten", data(cleared).get("action").getAsString());
    }
    @Test void externalChangesAndMissingTargetsConflict() throws Exception {
        Files.writeString(root.resolve("a"), "old");
        String hash = data(read("a")).get("sha256").getAsString();
        Files.writeString(root.resolve("a"), "outside change");
        var writer = new FileWriteTool(root);
        code("WRITE_CONFLICT", writer.execute(args("path", "a", "content", "new", "expected_sha256", hash)));
        assertEquals("outside change", Files.readString(root.resolve("a")));
        Files.delete(root.resolve("a"));
        code("WRITE_CONFLICT", writer.execute(args("path", "a", "content", "new", "expected_sha256", hash)));
        assertFalse(Files.exists(root.resolve("a")));
        code("INVALID_ARGUMENTS", writer.execute(args("path", "nested/a", "content", "new", "expected_sha256", "wrong")));
        assertFalse(Files.exists(root.resolve("nested")));
    }
    @Test void normalDevelopmentNamesAndNestedParentsWork() throws Exception {
        for (String name : List.of("Dockerfile", ".gitignore", "settings.toml", "nested/deep/run.ps1")) {
            var result = write(name, "text"); assertTrue(result.isSuccess(), result.getOutput());
        }
        code("ACCESS_DENIED", write(".git/hooks/run.sh", "bad"));
        assertFalse(Files.exists(root.resolve(".git")));
    }
    @Test void deniedSecretsNeverEnterOutputOrDirectoryListing() throws Exception {
        for (String name : List.of(".env", ".env.local", "local.env", "config.local.yaml", "id_rsa", "secret.pem", "secret.pfx")) {
            Files.writeString(root.resolve(name), "DO_NOT_LEAK");
            ToolResult result = read(name); code("ACCESS_DENIED", result);
            assertFalse(result.getOutput().contains("DO_NOT_LEAK"));
            code("ACCESS_DENIED", write(name, "changed"));
        }
        var listed = new FileListTool(root).execute("{\"path\":\".\"}");
        assertTrue(listed.isSuccess()); assertEquals(0, data(listed).getAsJsonArray("entries").size());
    }
    @Test void completeEncodedMessageBudgetAndByteLimitsAreEnforced() throws Exception {
        Files.writeString(root.resolve("large"), "x".repeat(8000));
        code("FILE_EXCEEDS_MESSAGE_BUDGET", read("large"));
        Files.writeString(root.resolve("escaped"), "\t".repeat(4100));
        code("FILE_EXCEEDS_MESSAGE_BUDGET", read("escaped"));
        Files.write(root.resolve("huge"), new byte[ToolLimits.FILE_BYTES + 1]);
        code("FILE_TOO_LARGE", read("huge"));
        code("FILE_TOO_LARGE", write("nested/huge", "x".repeat(ToolLimits.FILE_BYTES + 1)));
        assertFalse(Files.exists(root.resolve("nested")));
        Files.write(root.resolve("bad-encoding"), new byte[]{(byte) 0xc3, (byte) 0x28});
        code("UNSUPPORTED_ENCODING", read("bad-encoding"));
        Files.write(root.resolve("binary"), new byte[]{65, 0, 66});
        code("UNSUPPORTED_CONTENT", read("binary"));
        Files.writeString(root.resolve("good"), "\"\\\n你好");
        var result = read("good"); assertTrue(result.isSuccess());
        assertTrue(result.getOutput().length() <= ToolLimits.MESSAGE_CHARS);
        assertEquals(Files.readString(root.resolve("good")), data(result).get("content").getAsString());
    }
    @Test void twoAgentWritersCannotBothCreateTheSameTarget() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try {
            var results = pool.invokeAll(List.<Callable<ToolResult>>of(() -> write("same", "one"), () -> write("same", "two")));
            assertEquals(1, results.stream().filter(f -> { try { return f.get().isSuccess(); } catch (Exception e) { throw new AssertionError(e); } }).count());
            try (var files = Files.list(root)) { assertEquals(List.of("same"), files.map(p -> p.getFileName().toString()).toList()); }
        } finally { pool.shutdownNow(); }
    }
    @Test void pagesOverThousandEntriesHaveStableOrderAndNoGaps() throws Exception {
        for (int i = 0; i < 1100; i++) Files.createFile(root.resolve("f%04d.txt".formatted(i)));
        var tool = new FileListTool(root);
        List<String> paths = new ArrayList<>(); String cursor = null;
        do {
            JsonObject request = new JsonObject(); request.addProperty("path", "."); request.addProperty("limit", 1000);
            if (cursor != null) request.addProperty("cursor", cursor);
            var result = tool.execute(request.toString()); assertTrue(result.isSuccess(), result.getOutput());
            assertTrue(result.getOutput().length() <= ToolLimits.MESSAGE_CHARS);
            JsonObject page = data(result);
            for (var entry : page.getAsJsonArray("entries")) paths.add(entry.getAsJsonObject().get("path").getAsString());
            cursor = page.get("next_cursor").isJsonNull() ? null : page.get("next_cursor").getAsString();
        } while (cursor != null);
        assertEquals(1100, paths.size()); assertEquals(1100, new HashSet<>(paths).size());
        assertEquals(paths.stream().sorted().toList(), paths);
    }
    @Test void queryBoundCursorFilteringDepthAndSpecialNames() throws Exception {
        Files.createDirectories(root.resolve("sub/deep"));
        Files.writeString(root.resolve("a.txt"), "a"); Files.writeString(root.resolve("b.txt"), "b");
        Files.writeString(root.resolve("sub/c.txt"), "c"); Files.writeString(root.resolve("sub/deep/d.txt"), "d");
        var tool = new FileListTool(root);
        var page = data(tool.execute("{\"path\":\".\",\"limit\":1}"));
        String cursor = page.get("next_cursor").getAsString();
        JsonObject changed = new JsonObject(); changed.addProperty("path", "."); changed.addProperty("limit", 2); changed.addProperty("cursor", cursor);
        code("INVALID_CURSOR", tool.execute(changed.toString()));
        changed.addProperty("limit", 1); changed.addProperty("glob", "null");
        code("INVALID_CURSOR", tool.execute(changed.toString()));
        var filtered = tool.execute("{\"path\":\".\",\"recursive\":true,\"max_depth\":2,\"glob\":\"**/*.txt\"}");
        assertTrue(filtered.isSuccess(), filtered.getOutput());
        assertTrue(filtered.getOutput().contains("sub/c.txt")); assertFalse(filtered.getOutput().contains("deep/d.txt"));
        if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows")) {
            Files.createFile(root.resolve("quote\"\nfile"));
            assertTrue(tool.execute("{\"path\":\".\"}").getOutput().contains("quote\\\"\\nfile"));
        }
    }
}
