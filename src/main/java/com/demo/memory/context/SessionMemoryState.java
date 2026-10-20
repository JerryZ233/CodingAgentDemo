package com.demo.memory.context;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Organizing metadata does not itself change the raw-content revision. */
public record SessionMemoryState(long lastOrganizedRevision, Instant lastOrganizedAt,
                                 CompressionState compression) {
    public SessionMemoryState {
        if (lastOrganizedRevision < 0) throw new IllegalArgumentException("Negative organized revision");
        if (lastOrganizedAt == null && lastOrganizedRevision != 0) {
            throw new IllegalArgumentException("Organized revision requires a successful timestamp");
        }
        Objects.requireNonNull(compression, "compression");
        if (compression.version() > 0 && lastOrganizedAt == null) {
            throw new IllegalArgumentException("Compression requires a successful timestamp");
        }
    }

    public static SessionMemoryState empty() {
        return new SessionMemoryState(0, null, CompressionState.empty());
    }

    public boolean dirty(long contentRevision) {
        validateRevision(contentRevision);
        return contentRevision > lastOrganizedRevision;
    }

    /** Eligibility only; callers also check messages, active writers, and retry backoff. */
    public boolean eligible(long contentRevision, Instant now, Duration minimumInterval) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(minimumInterval, "minimumInterval");
        if (minimumInterval.isNegative()) throw new IllegalArgumentException("Negative minimum interval");
        validateRevision(contentRevision);
        return lastOrganizedAt == null || (dirty(contentRevision)
                && !now.isBefore(lastOrganizedAt)
                && Duration.between(lastOrganizedAt, now).compareTo(minimumInterval) >= 0);
    }

    public void validateRevision(long contentRevision) {
        if (contentRevision < lastOrganizedRevision) {
            throw new IllegalArgumentException("Organized revision exceeds content revision");
        }
    }
}
