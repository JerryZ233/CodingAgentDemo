package com.demo.tools.fs;

import com.demo.tools.support.*;
import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.*;

/** Bounded-memory lexicographic pagination; rescans do not provide directory snapshots. */
public final class DirectoryListing {
    private final WorkspaceFileAccess access;
    private final int scanLimit;
    private final Duration timeout;
    public DirectoryListing(WorkspaceFileAccess access) { this(access, 100_000, Duration.ofSeconds(5)); }
    DirectoryListing(WorkspaceFileAccess access, int scanLimit, Duration timeout) {
        this.access = access; this.scanLimit = scanLimit; this.timeout = timeout;
    }
    public JsonObject list(String path, int limit, String cursor, String glob, boolean recursive, int depth) throws IOException {
        Path directory = access.existing(path);
        if (!Files.isDirectory(directory)) throw new FileAccessException("NOT_A_DIRECTORY", "Expected directory");
        if (glob != null && glob.length() > ToolLimits.PATH_CHARS) throw new IllegalArgumentException("glob too long");
        JsonObject queryFields = new JsonObject();
        queryFields.addProperty("workspace", access.root().toString()); queryFields.addProperty("directory", directory.toString());
        queryFields.addProperty("limit", limit); queryFields.addProperty("glob", glob);
        queryFields.addProperty("recursive", recursive); queryFields.addProperty("depth", depth);
        String query = WorkspaceFileAccess.sha256(queryFields.toString().getBytes(StandardCharsets.UTF_8));
        String after = "";
        if (cursor != null) {
            if (cursor.length() > 16384) throw new IllegalArgumentException("cursor too long");
            try {
                var c = ToolArguments.parse(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8), "query", "after");
                if (!c.requiredString("query").equals(query)) throw new IllegalArgumentException("query changed");
                after = c.requiredString("after");
            } catch (IllegalArgumentException e) { throw new FileAccessException("INVALID_CURSOR", "Cursor does not match query"); }
        }
        PathMatcher matcher = glob == null ? p -> true : directory.getFileSystem().getPathMatcher("glob:" + glob);
        PriorityQueue<Entry> candidates = new PriorityQueue<>(Comparator.comparing(Entry::path).reversed());
        Scan scan = new Scan(System.nanoTime() + timeout.toNanos());
        visit(directory, directory, recursive ? depth : 1, matcher, after, limit + 1, candidates, scan);
        List<Entry> ordered = new ArrayList<>(candidates); ordered.sort(Comparator.comparing(Entry::path));
        int count = Math.min(limit, ordered.size());
        JsonObject result = page(ordered, count, query);
        if (result.toString().length() <= ToolLimits.MESSAGE_CHARS) return result;
        // Encoded entry sizes vary; binary search avoids repeatedly serializing nearly identical large pages.
        int low = 1, high = count - 1;
        JsonObject best = null;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            JsonObject candidate = page(ordered, middle, query);
            if (candidate.toString().length() <= ToolLimits.MESSAGE_CHARS) { best = candidate; low = middle + 1; }
            else high = middle - 1;
        }
        if (best != null) return best;
        throw new FileAccessException("RESULT_EXCEEDS_MESSAGE_BUDGET", "An entry cannot fit the message budget");
    }
    private void visit(Path queryRoot, Path directory, int remaining, PathMatcher matcher, String after,
                       int capacity, PriorityQueue<Entry> candidates, Scan scan) throws IOException {
        access.existing(access.relative(directory));
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path path : stream) {
                if (++scan.count > scanLimit || System.nanoTime() >= scan.deadline)
                    throw new FileAccessException("DIRECTORY_SCAN_LIMIT", "Directory scan exceeded item or time limit");
                BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                boolean link = a.isSymbolicLink() || a.isOther() || (a.isDirectory() && !path.toRealPath().equals(path));
                if (!access.visible(path, link)) continue;
                String relative = access.relative(path);
                if (matcher.matches(queryRoot.relativize(path)) && relative.compareTo(after) > 0) {
                    Entry entry = new Entry(path.getFileName().toString(), relative, link ? "link" : a.isDirectory() ? "directory" : "file");
                    candidates.add(entry);
                    if (candidates.size() > capacity) candidates.remove();
                }
                if (a.isDirectory() && !link && remaining > 1)
                    visit(queryRoot, path, remaining - 1, matcher, after, capacity, candidates, scan);
            }
        }
    }
    private JsonObject page(List<Entry> entries, int count, String query) {
        JsonObject result = new JsonObject(); JsonArray array = new JsonArray();
        for (int i = 0; i < count; i++) {
            Entry entry = entries.get(i); JsonObject item = new JsonObject();
            item.addProperty("name", entry.name()); item.addProperty("path", entry.path()); item.addProperty("type", entry.type());
            array.add(item);
        }
        result.add("entries", array);
        if (entries.size() > count && count > 0) {
            JsonObject cursor = new JsonObject(); cursor.addProperty("query", query); cursor.addProperty("after", entries.get(count - 1).path());
            result.addProperty("next_cursor", Base64.getUrlEncoder().withoutPadding().encodeToString(cursor.toString().getBytes(StandardCharsets.UTF_8)));
        } else result.add("next_cursor", JsonNull.INSTANCE);
        return result;
    }
    private record Entry(String name, String path, String type) { }
    private static final class Scan { int count; final long deadline; Scan(long deadline) { this.deadline = deadline; } }
}
