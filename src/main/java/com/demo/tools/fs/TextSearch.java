package com.demo.tools.fs;

import com.demo.execution.CancellationToken;
import com.demo.tools.support.ToolLimits;
import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.LongSupplier;

/** Bounded, deterministic depth-first literal search. Results are not filesystem snapshots. */
public final class TextSearch {
    private static final Set<String> EXCLUDED = Set.of(".git", ".gradle", "build", "node_modules");
    private final WorkspaceFileAccess access;
    private final int entryLimit;
    private final long byteLimit;
    private final long timeoutNanos;
    private final LongSupplier clock;

    public TextSearch(WorkspaceFileAccess access) {
        this(access, ToolLimits.SEARCH_SCAN_ENTRIES, ToolLimits.SEARCH_READ_BYTES,
                ToolLimits.SEARCH_TIMEOUT_MS * 1_000_000L, System::nanoTime);
    }
    TextSearch(WorkspaceFileAccess access, int entries, long bytes, long timeoutNanos, LongSupplier clock) {
        this.access = access; entryLimit = entries; byteLimit = bytes; this.timeoutNanos = timeoutNanos; this.clock = clock;
    }

    public JsonObject search(String path, String text, String glob, boolean sensitive, int maxResults,
                             int maxDepth, boolean explicitDepth, CancellationToken token) throws IOException {
        State state = new State(text, sensitive, maxResults, token, clock.getAsLong());
        token.throwIfCancellationRequested();
        Path root = access.existingNoLinks(path);
        BasicFileAttributes attrs = Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attrs.isRegularFile()) {
            if (glob != null || explicitDepth) throw new IllegalArgumentException("File query cannot have glob or max_depth");
            state.file(root);
        } else if (attrs.isDirectory()) {
            PathMatcher matcher = glob == null ? p -> true : root.getFileSystem().getPathMatcher("glob:" + glob);
            state.visit(root, root, maxDepth, matcher);
        } else throw new FileAccessException("NOT_A_FILE", "Expected a file or directory");
        access.verifyBinding();
        token.throwIfCancellationRequested();
        return state.finish();
    }

    private final class State {
        final String text;
        final boolean sensitive;
        final int maxResults;
        final CancellationToken token;
        final long started;
        final JsonArray matches = new JsonArray();
        final Set<String> reasons = new LinkedHashSet<>();
        final Map<String, Integer> skipped = new LinkedHashMap<>();
        int entries;
        long bytes;
        boolean stopped;

        State(String text, boolean sensitive, int maxResults, CancellationToken token, long started) {
            this.text = text; this.sensitive = sensitive; this.maxResults = maxResults; this.token = token; this.started = started;
            for (String key : List.of("unsupported_content", "too_large", "unreadable", "changed")) skipped.put(key, 0);
        }
        void stop(String reason) { reasons.add(reason); stopped = true; }
        boolean active() {
            token.throwIfCancellationRequested();
            if (!stopped && clock.getAsLong() - started >= timeoutNanos) stop("timeout");
            return !stopped;
        }
        void skip(String kind) { skipped.compute(kind, (key, count) -> count + 1); }

        void visit(Path queryRoot, Path directory, int depth, PathMatcher matcher) throws IOException {
            if (!active()) return;
            try {
                access.existingNoLinks(access.relative(directory));
                List<Path> children = new ArrayList<>();
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
                    for (Path child : stream) {
                        if (!active()) return;
                        if (++entries > entryLimit) { stop("scan_entries"); return; }
                        children.add(child);
                    }
                }
                children.sort(Comparator.comparing(p -> p.getFileName().toString()));
                for (Path child : children) {
                    if (!active()) return;
                    try {
                        if (!access.visible(child, false)) continue;
                        BasicFileAttributes attrs = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                        if (attrs.isSymbolicLink() || attrs.isOther()) continue;
                        if (attrs.isDirectory()) {
                            if (EXCLUDED.contains(child.getFileName().toString().toLowerCase(Locale.ROOT))) continue;
                            access.existingNoLinks(access.relative(child));
                            if (depth > 1) visit(queryRoot, child, depth - 1, matcher);
                            else reasons.add("max_depth");
                        } else if (attrs.isRegularFile() && matcher.matches(queryRoot.relativize(child))) file(child);
                    } catch (IOException | SecurityException e) { failure(e); }
                }
            } catch (IOException | SecurityException e) { failure(e); }
        }

        void failure(Exception error) throws IOException {
            if (error instanceof FileAccessException e) {
                switch (e.code()) {
                    case "WORKSPACE_CHANGED" -> throw e;
                    case "ACCESS_DENIED" -> { return; }
                    case "FILE_CHANGED" -> { skip("changed"); return; }
                    case "FILE_TOO_LARGE" -> { skip("too_large"); return; }
                    case "UNSUPPORTED_CONTENT", "UNSUPPORTED_ENCODING" -> { skip("unsupported_content"); return; }
                }
            }
            skip("unreadable");
        }

        void file(Path file) throws IOException {
            if (!active()) return;
            try {
                access.existingNoLinks(access.relative(file));
                long size = Files.size(file);
                if (size > ToolLimits.FILE_BYTES) { skip("too_large"); return; }
                long remaining = byteLimit - bytes;
                if (size > remaining || remaining <= 0) { stop("read_bytes"); return; }
                int cap = (int) Math.min(ToolLimits.FILE_BYTES, remaining);
                WorkspaceFileAccess.Snapshot snapshot;
                try { snapshot = access.read(access.relative(file), cap); }
                catch (FileAccessException e) {
                    // Failed reads may have consumed data; charge the full per-read cap conservatively.
                    bytes += cap;
                    if (e.code().equals("FILE_TOO_LARGE") && cap < ToolLimits.FILE_BYTES) { stop("read_bytes"); return; }
                    throw e;
                }
                bytes += snapshot.bytes().length;
                TextFileView view = new TextFileView(WorkspaceFileAccess.decode(snapshot.bytes()));
                for (int number = 1; number <= view.totalLines(); number++) {
                    if (!active()) return;
                    String line = view.line(number);
                    int offset = indexOf(line);
                    if (offset >= 0) {
                        matches.add(match(snapshot.path(), number, line, offset));
                        if (page().toString().length() > ToolLimits.MESSAGE_CHARS - 256) {
                            matches.remove(matches.size() - 1); stop("message_budget"); return;
                        }
                        if (matches.size() >= maxResults) { stop("max_results"); return; }
                    }
                }
            } catch (IOException | SecurityException e) { failure(e); }
        }

        int indexOf(String line) {
            if (sensitive) return line.indexOf(text);
            for (int i = 0; i <= line.length() - text.length(); i++) {
                if ((i & 1023) == 0 && !active()) return -1;
                if (line.regionMatches(true, i, text, 0, text.length())) return i;
            }
            return -1;
        }

        JsonObject match(String path, int number, String line, int offset) {
            int total = line.codePointCount(0, line.length());
            int matchStart = line.codePointCount(0, offset);
            int queryPoints = text.codePointCount(0, text.length());
            // A long query may exceed the preview cap; include at least the first matched character.
            int start = Math.max(0, matchStart - Math.max(0, (ToolLimits.SEARCH_PREVIEW_CODEPOINTS - queryPoints) / 2));
            start = Math.min(start, Math.max(0, total - ToolLimits.SEARCH_PREVIEW_CODEPOINTS));
            int end = Math.min(total, start + ToolLimits.SEARCH_PREVIEW_CODEPOINTS);
            JsonObject item = new JsonObject();
            item.addProperty("path", path); item.addProperty("line", number);
            item.addProperty("text", line.substring(line.offsetByCodePoints(0, start), line.offsetByCodePoints(0, end)));
            item.addProperty("text_truncated", start != 0 || end != total);
            return item;
        }

        JsonObject page() {
            JsonObject result = new JsonObject(); result.add("matches", matches);
            result.addProperty("returned_matches", matches.size());
            result.addProperty("complete", reasons.isEmpty() && skipped.values().stream().allMatch(count -> count == 0));
            JsonArray limits = new JsonArray(); reasons.forEach(limits::add); result.add("limit_reasons", limits);
            JsonObject counts = new JsonObject(); skipped.forEach(counts::addProperty); result.add("skipped_files", counts);
            return result;
        }
        JsonObject finish() {
            while (page().toString().length() > ToolLimits.MESSAGE_CHARS && !matches.isEmpty()) {
                matches.remove(matches.size() - 1); reasons.add("message_budget");
            }
            return page();
        }
    }
}
