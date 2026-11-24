package com.jamesstevens.epms.security.service;

import java.time.OffsetDateTime;
import java.util.Objects;

/** Single retry policy shared by entity and claimed JDBC recovery paths. */
public final class ScreeningRetryPolicy {
    private static final long[] RETRY_MINUTES = {1, 2, 4, 8, 15, 30, 60, 120, 240};
    public static final int MAX_RETRIES = RETRY_MINUTES.length;

    private ScreeningRetryPolicy() { }

    public static int nextAttemptCount(int completedAttempts) {
        if (completedAttempts < 0) {
            throw new IllegalArgumentException("Negative retry count");
        }
        return Math.min(MAX_RETRIES, completedAttempts + 1);
    }

    public static OffsetDateTime nextRetryAt(OffsetDateTime now,
            OffsetDateTime expiresAt, int completedAttempts) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (completedAttempts < 0) {
            throw new IllegalArgumentException("Negative retry count");
        }
        if (!now.isBefore(expiresAt)) {
            throw new IllegalStateException("Provisional screening window has expired");
        }
        OffsetDateTime next = expiresAt;
        if (completedAttempts < MAX_RETRIES) {
            OffsetDateTime proposed = now.plusMinutes(RETRY_MINUTES[completedAttempts]);
            next = proposed.isBefore(expiresAt) ? proposed : expiresAt;
        }
        return next;
    }
}
