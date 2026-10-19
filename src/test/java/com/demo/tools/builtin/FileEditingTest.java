package com.demo.tools.builtin;

import com.demo.model.ToolResult;
import com.demo.tools.fs.WorkspaceFileAccess;
import com.demo.tools.support.ToolLimits;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FileEditingTest {
    @TempDir Path root;
    private String args(Object... fields) {
        JsonObject json = new JsonObject();
        for (int i = 0; i < fields.length; i += 2) json.add(fields[i].toString(), new Gson().toJsonTree(fields[i + 1]));
        return json.toString();
    }
    private JsonObject ok(ToolResult result) {
        assertTrue(result.isSuccess(), result.getOutput());
        assertTrue(result.getOutput().length() <= ToolLimits.MESSAGE_CHARS);
        return JsonParser.parseString(result.getOutput()).getAsJsonObject();
    }
    private void error(String expected, ToolResult result) {
        assertFalse(result.isSuccess());
        assertEquals(expected, JsonParser.parseString(result.getOutput()).getAsJsonObject().get("code").getAsString());
    }
    private String hash(String path) throws Exception { return new WorkspaceFileAccess(root).read(path).sha256(); }
    private ToolResult replace(String old, String next, String hash) {
        return new FileWriteTool(root).execute(args("path", "a", "mode", "replace", "old_text", old, "new_text", next, "expected_sha256", hash));
    }

    @Test void replacementPreservesBomUnicodeAndExactLineEndingsAndChainsHashes() throws Exception {
        String original = "\ufeff你好😀\r\n  old\r\nlast";
        Files.writeString(root.resolve("a"), original);
        String before = hash("a");
        JsonObject result = ok(replace("  old\r\n", "  new😀\r\n", before));
        assertEquals("overwritten", result.get("action").getAsString());
        assertEquals(1, result.get("replacements").getAsInt());
        assertEquals(original.replace("old", "new😀"), Files.readString(root.resolve("a")));
        assertEquals(hash("a"), result.get("sha256").getAsString());
        error("WRITE_CONFLICT", replace("last", "next", before));
        ok(replace("last", "", result.get("sha256").getAsString()));
        assertTrue(Files.readString(root.resolve("a")).endsWith("\r\n"));
    }

    @Test void replacementFailuresNeverChangeOriginalOrCreateParents() throws Exception {
        Files.writeString(root.resolve("a"), "aaa\n  \n");
        String hash = hash("a");
        error("TEXT_NOT_UNIQUE", replace("aa", "b", hash)); // overlapping occurrences
        error("TEXT_NOT_FOUND", replace("missing", "b", hash));
        error("INVALID_ARGUMENTS", replace("", "b", hash));
        error("INVALID_ARGUMENTS", replace("aaa", "aaa", hash));
        error("INVALID_ARGUMENTS", replace("aaa", "b", "invalid"));
        error("INVALID_ARGUMENTS", new FileWriteTool(root).execute(args("path", "a", "mode", "replace", "content", "x")));
        error("INVALID_ARGUMENTS", new FileWriteTool(root).execute(args("path", "a", "content", "x", "old_text", "aaa")));
        error("NOT_FOUND", new FileWriteTool(root).execute(args("path", "missing/a", "mode", "replace", "old_text", "a", "new_text", "b", "expected_sha256", hash)));
        assertFalse(Files.exists(root.resolve("missing")));
        assertEquals("aaa\n  \n", Files.readString(root.resolve("a")));
        ok(replace("  ", "    ", hash)); // whitespace is valid original text
        assertEquals("aaa\n    \n", Files.readString(root.resolve("a")));
        try (var files = Files.list(root)) { assertEquals(1, files.count()); }
    }

    @Test void replacementChecksFinalFileSizeAndPolicy() throws Exception {
        Files.writeString(root.resolve("a"), "original");
        error("FILE_TOO_LARGE", replace("original", "x".repeat(ToolLimits.FILE_BYTES + 1), hash("a")));
        Files.writeString(root.resolve(".env"), "original");
        error("ACCESS_DENIED", new FileWriteTool(root).execute(args("path", ".env", "mode", "replace",
                "old_text", "original", "new_text", "changed", "expected_sha256", hash("a"))));
        assertEquals("original", Files.readString(root.resolve("a")));
    }

    @Test void rangeReadsPreserveAllNewlinesAndReportEofAndWholeFileHash() throws Exception {
        String original = "\ufeffone\r\ntwo\rthree\nfour";
        Files.writeString(root.resolve("a"), original);
        var reader = new FileReadTool(root);
        JsonObject first = ok(reader.execute(args("path", "a", "max_lines", 2)));
        assertEquals("\ufeffone\r\ntwo\r", first.get("content").getAsString());
        assertEquals(4, first.get("total_lines").getAsInt());
        assertEquals(hash("a"), first.get("sha256").getAsString());
        assertEquals("max_lines", first.get("limited_by").getAsString());
        JsonObject last = ok(reader.execute(args("path", "a", "start_line", 3, "expected_sha256", hash("a"))));
        assertEquals("three\nfour", last.get("content").getAsString());
        assertFalse(last.get("complete").getAsBoolean());
        assertFalse(last.get("has_more").getAsBoolean());
        assertTrue(last.get("next_start_line").isJsonNull());
        JsonObject eof = ok(reader.execute(args("path", "a", "start_line", 5)));
        assertEquals("", eof.get("content").getAsString());
        assertTrue(eof.get("end_line").isJsonNull());
        error("INVALID_ARGUMENTS", reader.execute(args("path", "a", "start_line", 6)));
        error("INVALID_ARGUMENTS", reader.execute(args("path", "a", "max_lines", 0)));
        String hash = hash("a");
        Files.writeString(root.resolve("a"), "changed");
        error("FILE_CHANGED", reader.execute(args("path", "a", "start_line", 3, "expected_sha256", hash)));
    }

    @Test void rangeBudgetCountsJsonEscapingAndCanReassembleLargeFile() throws Exception {
        String original = "你好😀\t\"\\\r\n".repeat(2000);
        Files.writeString(root.resolve("a"), original);
        var reader = new FileReadTool(root);
        error("FILE_EXCEEDS_MESSAGE_BUDGET", reader.execute(args("path", "a")));
        StringBuilder combined = new StringBuilder();
        int start = 1;
        while (true) {
            JsonObject page = ok(reader.execute(args("path", "a", "start_line", start, "max_lines", 1000, "expected_sha256", hash("a"))));
            combined.append(page.get("content").getAsString());
            if (!page.get("has_more").getAsBoolean()) break;
            assertEquals("message_budget", page.get("limited_by").getAsString());
            start = page.get("next_start_line").getAsInt();
        }
        assertEquals(original, combined.toString());
        Files.writeString(root.resolve("a"), "x".repeat(8000));
        error("LINE_EXCEEDS_MESSAGE_BUDGET", reader.execute(args("path", "a", "start_line", 1)));
    }

    @Test void emptyAndTrailingNewlineLineCounts() throws Exception {
        var reader = new FileReadTool(root);
        for (var test : Map.of("", 0, "\n", 1, "a\n", 1, "a\n\n", 2).entrySet()) {
            Files.writeString(root.resolve("a"), test.getKey());
            JsonObject page = ok(reader.execute(args("path", "a", "start_line", 1)));
            assertEquals(test.getValue(), page.get("total_lines").getAsInt());
            assertEquals(test.getKey(), page.get("content").getAsString());
            assertTrue(page.get("complete").getAsBoolean());
        }
    }
}
