package com.demo.tools.fs;

import com.demo.execution.*;
import com.demo.tools.builtin.SearchTextTool;
import com.demo.tools.support.ToolLimits;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TextSearchTest {
    @TempDir Path root;
    private TextSearch search() { return new TextSearch(new WorkspaceFileAccess(root)); }
    private JsonObject query(TextSearch search, String path, String text, String glob, int results, int depth) throws Exception {
        JsonObject result = search.search(path, text, glob, true, results, depth, false, CancellationToken.none());
        assertTrue(result.toString().length() <= ToolLimits.MESSAGE_CHARS);
        return result;
    }
    private boolean complete(JsonObject result) { return result.get("complete").getAsBoolean(); }
    private JsonArray matches(JsonObject result) { return result.getAsJsonArray("matches"); }
    private Path put(String path, String content) throws Exception {
        Path target = root.resolve(path); Files.createDirectories(target.getParent()); return Files.writeString(target, content);
    }
    private void reason(JsonObject result, String reason) {
        assertFalse(complete(result));
        assertTrue(result.getAsJsonArray("limit_reasons").asList().stream().anyMatch(v -> v.getAsString().equals(reason)), result.toString());
    }

    @Test void literalLineSearchFiltersByGlobAndHasDeterministicOrder() throws Exception {
        put("src/b.java", "needle needle\r\nother\rneedle😀");
        put("src/a.java", "needle\n"); put("src/no.txt", "needle");
        JsonObject result = query(search(), "src", "needle", "*.java", 50, 20);
        assertTrue(complete(result)); assertEquals(3, matches(result).size());
        assertEquals("src/a.java", matches(result).get(0).getAsJsonObject().get("path").getAsString());
        assertEquals(3, matches(result).get(2).getAsJsonObject().get("line").getAsInt());
        assertEquals(result, query(search(), "src", "needle", "*.java", 50, 20));
        assertEquals(2, matches(query(search(), "src/b.java", "needle", null, 50, 20)).size());
        assertThrows(IllegalArgumentException.class, () -> query(search(), "src/b.java", "needle", "*", 50, 20));
        assertThrows(IllegalArgumentException.class, () -> search().search("src/b.java", "needle", null, true, 50, 2, true, CancellationToken.none()));
    }

    @Test void caseInsensitiveWhitespaceAndUnicodeAreSupportedWithoutRegex() throws Exception {
        put("a", "Hello\n你好😀\n   \na.b\naxb");
        JsonObject result = search().search("a", "hello", null, false, 50, 20, false, CancellationToken.none());
        assertEquals(1, matches(result).size());
        assertEquals(0, matches(query(search(), "a", "hello", null, 50, 20)).size());
        assertEquals(1, matches(query(search(), "a", "你好😀", null, 50, 20)).size());
        assertEquals(1, matches(query(search(), "a", "  ", null, 50, 20)).size());
        assertEquals(1, matches(query(search(), "a", "a.b", null, 50, 20)).size());
    }

    @Test void protectedFilesAndExcludedDirectoriesDoNotLeakAndExplicitRootIsAllowed() throws Exception {
        put("safe.txt", "needle"); put(".env", "DO_NOT_LEAK needle"); put("config.local.yaml", "DO_NOT_LEAK needle");
        put(".git/config", "DO_NOT_LEAK needle"); put("build/output", "needle"); put("node_modules/package/a", "needle");
        JsonObject result = query(search(), ".", "needle", null, 50, 20);
        assertTrue(complete(result)); assertEquals(1, matches(result).size());
        assertFalse(result.toString().contains("DO_NOT_LEAK"));
        assertEquals(1, matches(query(search(), "build", "needle", null, 50, 20)).size());
        var denied = new SearchTextTool(root).execute("{\"path\":\".env\",\"text\":\"needle\"}");
        assertFalse(denied.isSuccess()); assertFalse(denied.getOutput().contains("DO_NOT_LEAK"));
        assertFalse(new SearchTextTool(root).execute("{\"path\":\"../\",\"text\":\"needle\"}").isSuccess());
    }

    @Test void skipsUnsupportedAndLargeFilesAndDoesNotClaimAbsence() throws Exception {
        Files.write(root.resolve("binary"), new byte[]{0, 1});
        Files.write(root.resolve("invalid"), new byte[]{(byte) 0xc3, (byte) 0x28});
        Files.write(root.resolve("large"), new byte[ToolLimits.FILE_BYTES + 1]);
        JsonObject result = query(search(), ".", "missing", null, 50, 20);
        assertFalse(complete(result)); assertEquals(0, matches(result).size());
        assertEquals(2, result.getAsJsonObject("skipped_files").get("unsupported_content").getAsInt());
        assertEquals(1, result.getAsJsonObject("skipped_files").get("too_large").getAsInt());
    }

    @Test void previewsUseUnicodeBoundariesAndJsonBudgetIsRespected() throws Exception {
        put("a", "😀".repeat(1000) + "needle" + "😀".repeat(1000));
        JsonObject match = matches(query(search(), "a", "needle", null, 50, 20)).get(0).getAsJsonObject();
        String preview = match.get("text").getAsString();
        assertTrue(preview.contains("needle")); assertEquals(500, preview.codePointCount(0, preview.length()));
        assertTrue(match.get("text_truncated").getAsBoolean());
        put("a", ("\t".repeat(400) + "needle\n").repeat(100));
        JsonObject result = query(search(), "a", "needle", null, 200, 20);
        reason(result, "message_budget");
        assertTrue(matches(result).size() > 0);
    }

    @Test void scanByteDepthResultAndTimeLimitsAreExplicit() throws Exception {
        put("a", "needle\nneedle\n"); put("dir/b", "needle");
        reason(query(search(), "a", "needle", null, 1, 20), "max_results");
        reason(query(search(), ".", "needle", null, 50, 1), "max_depth");
        var access = new WorkspaceFileAccess(root);
        reason(query(new TextSearch(access, 1, 1000, Long.MAX_VALUE, () -> 0), ".", "needle", null, 50, 20), "scan_entries");
        reason(query(new TextSearch(access, 100, 3, Long.MAX_VALUE, () -> 0), ".", "needle", null, 50, 20), "read_bytes");
        AtomicLong ticks = new AtomicLong();
        reason(query(new TextSearch(access, 100, 1000, 3, ticks::getAndIncrement), ".", "needle", null, 50, 20), "timeout");
    }

    @Test void cancellationDuringScanPropagatesInsteadOfReturningSuccess() throws Exception {
        put("a", "needle\n".repeat(100));
        AtomicLong ticks = new AtomicLong();
        CancellationToken token = new CancellationToken() {
            public boolean isCancellationRequested() { return ticks.incrementAndGet() > 10; }
            public Registration onCancellation(Runnable action) { return () -> {}; }
        };
        assertThrows(OperationCancelledException.class,
                () -> search().search("a", "missing", null, true, 50, 20, false, token));
        CancellationSource source = new CancellationSource(); source.cancel();
        assertThrows(OperationCancelledException.class,
                () -> new SearchTextTool(root).execute("{\"text\":\"needle\"}", source.token()));
    }

    @Test void explicitAndDiscoveredLinksAreNeverFollowed() throws Exception {
        put("real/a", "needle");
        try { Files.createSymbolicLink(root.resolve("alias"), root.resolve("real")); }
        catch (IOException | UnsupportedOperationException | SecurityException e) { assumeTrue(false, "Symlinks unavailable"); }
        JsonObject result = query(search(), ".", "needle", null, 50, 20);
        assertEquals(1, matches(result).size());
        assertEquals("ACCESS_DENIED", assertThrows(FileAccessException.class,
                () -> query(search(), "alias", "needle", null, 50, 20)).code());
    }

    @Test void strictArgumentsAndInvalidQueriesAreRejected() throws Exception {
        var tool = new SearchTextTool(root);
        for (String invalid : List.of("{}", "{\"text\":null}", "{\"text\":\"\"}",
                "{\"text\":\"a\\nb\"}", "{\"text\":\"a\",\"extra\":1}", "{\"text\":\"a\",\"max_results\":0}")) {
            var result = tool.execute(invalid); assertFalse(result.isSuccess(), invalid);
            assertEquals("INVALID_ARGUMENTS", JsonParser.parseString(result.getOutput()).getAsJsonObject().get("code").getAsString());
        }
        assertFalse(tool.execute("{\"path\":\"missing\",\"text\":\"x\"}").isSuccess());
        var search = search(); Files.move(root, root.resolveSibling(root.getFileName() + "-moved"));
        Files.createDirectory(root);
        assertEquals("WORKSPACE_CHANGED", assertThrows(FileAccessException.class,
                () -> query(search, ".", "x", null, 50, 20)).code());
        // Return the moved directory to its original location for temp-directory cleanup.
        Files.delete(root); Files.move(root.resolveSibling(root.getFileName() + "-moved"), root);
    }
}
