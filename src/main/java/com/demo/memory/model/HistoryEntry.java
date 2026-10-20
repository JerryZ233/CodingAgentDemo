package com.demo.memory.model;

import com.demo.model.Message;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record HistoryEntry(String id, String operationId, String sessionId, long sourceRevision,
                           List<String> coveredMessageIds, String previousSummaryId,
                           String archiveSummary, String continuationSummary, List<String> keywords,
                           List<String> sourceRefs, Instant createdAt, String promptVersion) {
    public HistoryEntry {
        Message.requireId(id);
        Message.requireId(operationId);
        Message.requireId(sessionId);
        if (sourceRevision <= 0) throw new IllegalArgumentException("Invalid history revision");
        coveredMessageIds = List.copyOf(coveredMessageIds);
        if (coveredMessageIds.isEmpty() || new HashSet<>(coveredMessageIds).size() != coveredMessageIds.size()) {
            throw new IllegalArgumentException("History requires unique covered messages");
        }
        coveredMessageIds.forEach(Message::requireId);
        if (sourceRevision < coveredMessageIds.size()) throw new IllegalArgumentException("History revision predates its messages");
        if (previousSummaryId != null) {
            Message.requireId(previousSummaryId);
            if (previousSummaryId.equals(id)) throw new IllegalArgumentException("History cannot reference itself");
        }
        if (archiveSummary == null || archiveSummary.isBlank() || continuationSummary == null || continuationSummary.isBlank()
                || promptVersion == null || promptVersion.isBlank()) throw new IllegalArgumentException("Missing history summaries/version");
        keywords = List.copyOf(keywords);
        if (keywords.stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("Blank history keyword");
        sourceRefs = List.copyOf(sourceRefs);
        if (!coveredMessageIds.containsAll(sourceRefs)) throw new IllegalArgumentException("Unknown history source");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public String coveredThroughMessageId() { return coveredMessageIds.get(coveredMessageIds.size() - 1); }
}
