package com.demo.session;

import com.demo.session.storage.SessionStorageException;
import java.util.Comparator;
import java.util.List;

public record SessionCatalog(List<SessionSummary> sessions, List<LoadIssue> issues) {
    public SessionCatalog {
        sessions = sessions.stream().sorted(Comparator.comparing(SessionSummary::updatedAt).reversed()
                .thenComparing(SessionSummary::id)).toList();
        issues = List.copyOf(issues);
    }

    public record LoadIssue(String fileName, SessionStorageException.Reason reason, String message) { }
}
