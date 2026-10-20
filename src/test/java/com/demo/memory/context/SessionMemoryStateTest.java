package com.demo.memory.context;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SessionMemoryStateTest {
    private final Instant now = Instant.parse("2026-10-20T00:00:00Z");
    private final Duration interval = Duration.ofHours(24);

    @Test void firstOrganizationIgnoresIntervalAndLaterRequiresBothDirtyAndElapsedTime() {
        assertTrue(SessionMemoryState.empty().eligible(10, now, interval));
        var state = new SessionMemoryState(10, now, CompressionState.empty());
        assertFalse(state.dirty(10));
        assertTrue(state.dirty(11));
        assertFalse(state.eligible(11, now.plus(interval).minusNanos(1), interval));
        assertFalse(state.eligible(10, now.plus(interval), interval));
        assertTrue(state.eligible(11, now.plus(interval), interval));
        assertFalse(state.eligible(11, now.minusSeconds(1), interval));
        assertThrows(IllegalArgumentException.class, () -> state.dirty(9));
    }

    @Test void rejectsInconsistentMarkersAndNegativeVersions() {
        assertThrows(IllegalArgumentException.class, () -> new SessionMemoryState(1, null, CompressionState.empty()));
        assertThrows(IllegalArgumentException.class, () -> new SessionMemoryState(-1, now, CompressionState.empty()));
        assertThrows(IllegalArgumentException.class, () -> new CompressionState(null, null, "", -1));
        assertThrows(IllegalArgumentException.class, () -> new CompressionState(null, "history", "text", 1));
    }
}
