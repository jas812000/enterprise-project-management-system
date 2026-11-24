package com.jamesstevens.epms.security.web;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Immutable deadline rules for server-managed authentication sessions. */
public final class SessionDeadlinePolicy {
    public static final Duration AUTHENTICATION_VALIDITY = Duration.ofMinutes(15);
    public static final Duration IDLE_LIMIT = Duration.ofMinutes(30);
    public static final Duration ABSOLUTE_LIMIT = Duration.ofDays(7);

    public enum Result { VALID, REAUTHENTICATION_REQUIRED, IDLE_EXPIRED, ABSOLUTE_EXPIRED }

    private SessionDeadlinePolicy() { }

    /** Expiration is inclusive: a deadline is expired at its exact instant. */
    @SuppressWarnings("PMD.OnlyOneReturn") // Ordered deadline checks preserve first-expired precedence.
    public static Result evaluate(Instant now, Instant sessionCreated,
            Instant authenticatedAt, Instant lastActivityAt) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(sessionCreated, "sessionCreated");
        Objects.requireNonNull(authenticatedAt, "authenticatedAt");
        Objects.requireNonNull(lastActivityAt, "lastActivityAt");
        if (sessionCreated.isAfter(now) || authenticatedAt.isAfter(now)
                || lastActivityAt.isAfter(now) || authenticatedAt.isBefore(sessionCreated)
                || lastActivityAt.isBefore(sessionCreated)) {
            throw new IllegalArgumentException("Invalid session timestamp ordering");
        }
        if (!now.isBefore(sessionCreated.plus(ABSOLUTE_LIMIT))) {
            return Result.ABSOLUTE_EXPIRED;
        }
        if (!now.isBefore(lastActivityAt.plus(IDLE_LIMIT))) {
            return Result.IDLE_EXPIRED;
        }
        if (!now.isBefore(authenticatedAt.plus(AUTHENTICATION_VALIDITY))) {
            return Result.REAUTHENTICATION_REQUIRED;
        }
        return Result.VALID;
    }
}
