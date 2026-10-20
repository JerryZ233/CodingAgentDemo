package com.demo.memory.history;

import com.demo.memory.context.TokenEstimator;
import com.demo.memory.model.HistoryEntry;
import com.demo.memory.ProjectLocator;
import com.demo.execution.CancellationToken;
import com.demo.memory.storage.HistoryRepository;
import com.demo.memory.storage.MemoryStorageException;
import com.demo.model.Message;
import com.demo.session.SessionSnapshot;
import com.demo.session.storage.SessionRepository;
import com.demo.session.storage.SessionStorageException;
import com.demo.tools.support.ToolLimits;
import com.google.gson.*;
import java.io.IOException;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Read-only lexical search, bound to a host-resolved project and the configured session repository. */
public final class HistorySearchService {
    public static final int DEFAULT_RESULTS = 5, MAX_RESULTS = 10, MAX_QUERY_CODE_POINTS = 1024;
    public static final int OUTPUT_TOKENS = 2000;
    private static final Pattern TERMS = Pattern.compile("[\\p{IsHan}]+|[\\p{L}\\p{N}_]+");
    private final ProjectLocator locator = new ProjectLocator();
    private final ProjectLocator.Project project;
    private final SessionRepository sessions;
    private final HistoryRepository history;
    private final TokenEstimator estimator = new TokenEstimator();
    private record Scored(HistoryEntry entry, int score) { }
    private record Source(String status, SessionSnapshot snapshot) { }

    public HistorySearchService(Path workspace, SessionRepository sessions) {
        this.sessions = Objects.requireNonNull(sessions);
        try { project = locator.locate(workspace); }
        catch (IOException | SecurityException failure) {
            throw new HistoryAccessException(HistoryAccessException.Reason.PROJECT_UNAVAILABLE, failure);
        }
        history = new HistoryRepository(project.memoryDirectory());
    }

    public JsonObject search(String query, int maxResults, CancellationToken token) {
        if (query == null || query.isBlank() || count(query) > MAX_QUERY_CODE_POINTS || query.indexOf('\0') >= 0
                || maxResults < 1 || maxResults > MAX_RESULTS) throw new IllegalArgumentException("Invalid history query");
        var snapshot = load(token);
        String normalized = normalize(query.strip());
        Set<String> terms = terms(normalized);
        if (terms.isEmpty()) throw new IllegalArgumentException("Query requires words or identifiers");
        List<Scored> matches = new ArrayList<>();
        for (var entry : snapshot.entries()) {
            token.throwIfCancellationRequested();
            int score = score(entry, normalized, terms);
            if (score > 0) matches.add(new Scored(entry, score));
        }
        matches.sort(Comparator.comparingInt(Scored::score).reversed()
                .thenComparing(value -> value.entry().createdAt(), Comparator.reverseOrder())
                .thenComparing(value -> value.entry().id()));
        JsonObject result = new JsonObject();
        JsonArray hits = new JsonArray();
        result.add("results", hits);
        result.addProperty("matched_count", matches.size());
        result.addProperty("complete", false); // Reserve space for final flags and reasons.
        Set<String> reasons = new LinkedHashSet<>();
        if (snapshot.incompleteTail()) reasons.add("INCOMPLETE_HISTORY_TAIL");
        if (matches.size() > maxResults) reasons.add("RESULT_LIMIT");
        for (var match : matches.subList(0, Math.min(maxResults, matches.size()))) {
            token.throwIfCancellationRequested();
            var entry = match.entry();
            var hit = identity(entry);
            String preview = preview(entry.archiveSummary(), terms, 160);
            hit.addProperty("summary_preview", preview);
            hit.addProperty("summary_truncated", !preview.equals(entry.archiveSummary()));
            hit.add("keywords", strings(entry.keywords().stream().limit(5).map(value -> clip(value, 32)).toList()));
            hit.addProperty("keywords_truncated", entry.keywords().size() > 5 || entry.keywords().stream().anyMatch(value -> count(value) > 32));
            hit.add("source_refs", strings(entry.sourceRefs().stream().limit(2).toList()));
            hit.addProperty("source_refs_truncated", entry.sourceRefs().size() > 2);
            hit.addProperty("source_status", source(entry, token).status());
            hits.add(hit);
            if (!fits(result, 128, 256)) {
                hits.remove(hits.size() - 1);
                reasons.add("OUTPUT_BUDGET");
                break;
            }
        }
        result.addProperty("returned_count", hits.size());
        result.addProperty("complete", reasons.isEmpty());
        result.add("limit_reasons", strings(reasons));
        token.throwIfCancellationRequested();
        return result;
    }

    public JsonObject read(String historyId, boolean includeSources, CancellationToken token) {
        Message.requireId(historyId);
        var snapshot = load(token);
        var entry = snapshot.entries().stream().filter(value -> value.id().equals(historyId)).findFirst()
                .orElseThrow(() -> new HistoryAccessException(HistoryAccessException.Reason.NOT_FOUND));
        var source = source(entry, token);
        var result = identity(entry);
        result.addProperty("archive_summary", entry.archiveSummary());
        result.addProperty("continuation_summary", entry.continuationSummary());
        result.add("keywords", strings(entry.keywords().stream().limit(5).map(value -> clip(value, 32)).toList()));
        result.addProperty("keywords_truncated", entry.keywords().size() > 5 || entry.keywords().stream().anyMatch(value -> count(value) > 32));
        result.add("source_refs", strings(entry.sourceRefs().stream().limit(8).toList()));
        result.addProperty("source_refs_truncated", entry.sourceRefs().size() > 8);
        result.addProperty("source_status", source.status());
        result.addProperty("include_sources", includeSources);
        result.addProperty("archive_summary_truncated", false);
        result.addProperty("continuation_summary_truncated", false);
        Set<String> reasons = new LinkedHashSet<>();
        if (snapshot.incompleteTail()) reasons.add("INCOMPLETE_HISTORY_TAIL");
        if (entry.sourceRefs().size() > 8 || entry.keywords().size() > 5
                || entry.keywords().stream().anyMatch(value -> count(value) > 32)) reasons.add("METADATA_LIMIT");
        while (!fits(result, 128, 256)) {
            String field = count(result.get("archive_summary").getAsString()) >= count(result.get("continuation_summary").getAsString())
                    ? "archive_summary" : "continuation_summary";
            String text = result.get(field).getAsString();
            result.addProperty(field, clip(text, count(text) / 2));
            result.addProperty(field + "_truncated", true);
            reasons.add("OUTPUT_BUDGET");
        }
        JsonArray snippets = new JsonArray();
        result.add("sources", snippets);
        if (includeSources && source.snapshot() != null) {
            var ids = entry.sourceRefs().isEmpty() ? entry.coveredMessageIds() : entry.sourceRefs();
            var byId = new HashMap<String, Message>();
            source.snapshot().messages().forEach(message -> byId.put(message.getId(), message));
            for (String id : new LinkedHashSet<>(ids)) {
                token.throwIfCancellationRequested();
                var message = byId.get(id);
                var snippet = new JsonObject();
                snippet.addProperty("message_id", id);
                snippet.addProperty("role", message.getRole());
                String text = sourceText(message);
                String shown = clip(text, 1000);
                snippet.addProperty("content", shown);
                snippet.addProperty("content_truncated", !shown.equals(text));
                snippets.add(snippet);
                while (!fits(result, 128, 256) && count(shown) > 64) {
                    shown = clip(shown, count(shown) / 2);
                    snippet.addProperty("content", shown);
                    snippet.addProperty("content_truncated", true);
                }
                if (!fits(result, 128, 256)) {
                    snippets.remove(snippets.size() - 1);
                    reasons.add("OUTPUT_BUDGET");
                    break;
                }
                if (snippet.get("content_truncated").getAsBoolean()) reasons.add("SOURCE_PREVIEW_LIMIT");
            }
            if (snippets.size() < new HashSet<>(ids).size()) reasons.add("OUTPUT_BUDGET");
        } else if (includeSources) reasons.add("SOURCE_UNAVAILABLE");
        result.addProperty("complete", reasons.isEmpty());
        result.add("limit_reasons", strings(reasons));
        token.throwIfCancellationRequested();
        return result;
    }

    private HistoryRepository.Snapshot load(CancellationToken token) {
        token.throwIfCancellationRequested();
        try {
            if (!locator.locate(project.workspace()).root().equals(project.root())) {
                throw new HistoryAccessException(HistoryAccessException.Reason.PROJECT_CHANGED);
            }
            var result = history.read(token);
            token.throwIfCancellationRequested();
            return result;
        } catch (IOException | SecurityException failure) {
            throw new HistoryAccessException(HistoryAccessException.Reason.PROJECT_UNAVAILABLE, failure);
        } catch (MemoryStorageException failure) {
            throw new HistoryAccessException(HistoryAccessException.Reason.HISTORY_UNAVAILABLE, failure);
        }
    }

    private Source source(HistoryEntry entry, CancellationToken token) {
        token.throwIfCancellationRequested();
        try {
            var snapshot = sessions.load(entry.sessionId());
            token.throwIfCancellationRequested();
            if (!locator.locate(snapshot.workspace()).root().equals(project.root())) return new Source("PROJECT_MISMATCH", null);
            int start = -1;
            for (int i = 0; i < snapshot.messages().size(); i++) {
                if (snapshot.messages().get(i).getId().equals(entry.coveredMessageIds().get(0))) { start = i; break; }
            }
            int end = start + entry.coveredMessageIds().size();
            if (!snapshot.id().equals(entry.sessionId()) || snapshot.contentRevision() < entry.sourceRevision() || start < 0
                    || end > snapshot.messages().size() || !snapshot.messages().subList(start, end).stream().map(Message::getId).toList()
                        .equals(entry.coveredMessageIds())) return new Source("INVALID_SOURCE", null);
            return new Source("AVAILABLE", snapshot);
        } catch (SessionStorageException failure) {
            return new Source(failure.getReason() == SessionStorageException.Reason.NOT_FOUND ? "NOT_FOUND" : "UNREADABLE", null);
        } catch (IOException | SecurityException failure) { return new Source("UNREADABLE", null); }
    }

    private static int score(HistoryEntry entry, String query, Set<String> terms) {
        String summary = normalize(entry.archiveSummary());
        String keywords = normalize(String.join(" ", entry.keywords()));
        Set<String> summaryTerms = terms(summary), keywordTerms = terms(keywords);
        int score = (containsPhrase(summary, query) ? 8 : 0) + (containsPhrase(keywords, query) ? 16 : 0);
        for (String term : terms) {
            if (summaryTerms.contains(term)) score += 2;
            if (keywordTerms.contains(term)) score += 6;
        }
        return score;
    }

    private static Set<String> terms(String text) {
        Set<String> result = new LinkedHashSet<>();
        var matcher = TERMS.matcher(text);
        while (matcher.find()) {
            String term = matcher.group();
            if (Character.UnicodeScript.of(term.codePointAt(0)) == Character.UnicodeScript.HAN && count(term) > 1) {
                int[] points = term.codePoints().toArray();
                for (int i = 0; i < points.length - 1; i++) result.add(new String(points, i, 2));
            } else result.add(term);
        }
        return result;
    }

    private static String normalize(String text) { return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT); }
    private static boolean containsPhrase(String text, String query) {
        for (int from = 0, match; (match = text.indexOf(query, from)) >= 0; from = match + 1) {
            int end = match + query.length();
            if ((!asciiWord(query.charAt(0)) || match == 0 || !asciiWord(text.charAt(match - 1)))
                    && (!asciiWord(query.charAt(query.length() - 1)) || end == text.length() || !asciiWord(text.charAt(end)))) return true;
        }
        return false;
    }
    private static boolean asciiWord(char value) { return value >= 'a' && value <= 'z' || value >= '0' && value <= '9' || value == '_'; }
    private static int count(String text) { return text.codePointCount(0, text.length()); }
    private static String clip(String text, int maxPoints) {
        return count(text) <= maxPoints ? text : text.substring(0, text.offsetByCodePoints(0, maxPoints));
    }
    private static String preview(String text, Set<String> terms, int length) {
        if (count(text) <= length) return text;
        String lower = text.toLowerCase(Locale.ROOT);
        int match = terms.stream().mapToInt(lower::indexOf).filter(index -> index >= 0).min().orElse(0);
        if (lower.length() != text.length()) match = 0;
        if (match > 0 && Character.isLowSurrogate(text.charAt(match))) match--;
        int start = Math.max(0, text.codePointCount(0, match) - 40);
        String tail = text.substring(text.offsetByCodePoints(0, start));
        return (start > 0 ? "…" : "") + clip(tail, length) + (count(tail) > length ? "…" : "");
    }
    private boolean fits(JsonObject result, int reservedTokens, int reservedChars) {
        String json = result.toString();
        return json.length() <= ToolLimits.MESSAGE_CHARS - reservedChars && estimator.text(json) <= OUTPUT_TOKENS - reservedTokens;
    }
    private static JsonArray strings(Collection<String> values) {
        JsonArray result = new JsonArray();
        values.forEach(result::add);
        return result;
    }
    private static JsonObject identity(HistoryEntry entry) {
        var result = new JsonObject();
        result.addProperty("history_id", entry.id());
        result.addProperty("session_id", entry.sessionId());
        result.addProperty("created_at", entry.createdAt().toString());
        return result;
    }
    private static String sourceText(Message message) {
        if (message.getToolCalls() != null && !message.getToolCalls().isEmpty()) return message.toJson();
        if (message.getContent() != null && !message.getContent().isEmpty()) return message.getContent();
        return "";
    }
}
