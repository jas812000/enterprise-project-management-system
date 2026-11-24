package com.jamesstevens.epms.security.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Pure lifecycle rules. Persistence and session enforcement are integrated separately. */
public final class ProvisionalCredentialLifecycle {
    public static final Duration VALIDITY = Duration.ofHours(12);
    public enum State { VERIFIED, PENDING_VERIFICATION, CHANGE_REQUIRED, EXPIRED }
    public enum Outcome { ALLOW_PROVISIONAL, REQUIRE_RESET, REQUIRE_PASSWORD_CHANGE, ALLOW_VERIFIED }
    private final Clock clock;

    public ProvisionalCredentialLifecycle(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    public Instant expirationFor(Instant issuedAt) {
        return Objects.requireNonNull(issuedAt).plus(VALIDITY);
    }

    public Outcome evaluate(State state, Instant issuedAt, Instant expiresAt) {
        Objects.requireNonNull(state);
        return switch (state) {
            case VERIFIED -> Outcome.ALLOW_VERIFIED;
            case CHANGE_REQUIRED -> Outcome.REQUIRE_PASSWORD_CHANGE;
            case EXPIRED -> Outcome.REQUIRE_RESET;
            case PENDING_VERIFICATION -> {
                if (issuedAt == null || expiresAt == null
                        || !expirationFor(issuedAt).equals(expiresAt)) {
                    yield Outcome.REQUIRE_RESET;
                }
                yield clock.instant().isBefore(expiresAt)
                        ? Outcome.ALLOW_PROVISIONAL : Outcome.REQUIRE_RESET;
            }
        };
    }

    /** A late verification response must not mutate a replacement credential. */
    public static boolean appliesToCurrentCredential(String screenedVersion,
                                                     String currentVersion) {
        return screenedVersion != null && screenedVersion.equals(currentVersion);
    }
}
