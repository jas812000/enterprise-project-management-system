package com.jamesstevens.epms.security.service;

import static org.junit.jupiter.api.Assertions.*;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class ScreeningRetryPolicyTest {
    @Test void approvedNineRetriesAndHardExpiration() {
        OffsetDateTime issued = OffsetDateTime.parse("2026-11-20T12:00:00Z");
        OffsetDateTime expires = issued.plusHours(12);
        long[] delays = {1, 2, 4, 8, 15, 30, 60, 120, 240};
        OffsetDateTime now = issued;
        for (int i = 0; i < delays.length; i++) {
            OffsetDateTime next = ScreeningRetryPolicy.nextRetryAt(now, expires, i);
            assertEquals(now.plusMinutes(delays[i]), next);
            assertEquals(i + 1, ScreeningRetryPolicy.nextAttemptCount(i));
            now = next;
        }
        assertEquals(issued.plusHours(8), now);
        assertEquals(expires, ScreeningRetryPolicy.nextRetryAt(now, expires, 9));
        assertEquals(9, ScreeningRetryPolicy.nextAttemptCount(9));
        assertEquals(expires, ScreeningRetryPolicy.nextRetryAt(issued.plusHours(11), expires, 100));
        assertEquals(expires, ScreeningRetryPolicy.nextRetryAt(issued.plusHours(11).plusMinutes(59), expires, 0));
        assertThrows(IllegalStateException.class,
                () -> ScreeningRetryPolicy.nextRetryAt(expires, expires, 0));
        assertThrows(IllegalArgumentException.class,
                () -> ScreeningRetryPolicy.nextAttemptCount(-1));
    }
}
