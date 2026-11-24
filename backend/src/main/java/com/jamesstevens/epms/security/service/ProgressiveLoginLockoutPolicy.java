package com.jamesstevens.epms.security.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Approved EPMS progressive authentication lockout policy. */
public final class ProgressiveLoginLockoutPolicy {
    private static final int FINAL_LOCKOUT_STAGE = 3;
    private static final int[] LIMITS = {5, 4, 3, 2};
    private static final Duration[] DELAYS = {
            Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofMinutes(15)};

    private ProgressiveLoginLockoutPolicy() { }

    public record State(int stage, int failuresInStage, Instant lockedUntil,
                        boolean administratorUnlockRequired) {
        public State {
            if (stage < 0 || stage > FINAL_LOCKOUT_STAGE || failuresInStage < 0) {
                throw new IllegalArgumentException("Invalid authentication lockout state");
            }
        }
    }

    public static State initial() { return new State(0, 0, null, false); }

    public static boolean isBlocked(State state, Instant now) {
        Objects.requireNonNull(state);
        Objects.requireNonNull(now);
        return state.administratorUnlockRequired()
                || (state.lockedUntil() != null && now.isBefore(state.lockedUntil()));
    }

    /** Call only for a rejected password check while the account is not locked. */
    public static State failure(State state, Instant now) {
        Objects.requireNonNull(state);
        Objects.requireNonNull(now);
        if (isBlocked(state, now)) {
            throw new IllegalStateException("Locked attempts are audited, not counted");
        }
        int next = state.failuresInStage() + 1;
        State result;
        if (next < LIMITS[state.stage()]) {
            result = new State(state.stage(), next, null, false);
        } else if (state.stage() == FINAL_LOCKOUT_STAGE) {
            result = new State(FINAL_LOCKOUT_STAGE, next, null, true);
        } else {
            result = new State(state.stage() + 1, 0, now.plus(DELAYS[state.stage()]), false);
        }
        return result;
    }

    public static State successfulAuthentication(State state, Instant now) {
        if (isBlocked(state, now)) {
            throw new IllegalStateException("Account locked");
        }
        return initial();
    }

    /** Requires a separately authorized and audited administrative action. */
    public static State administratorUnlock() { return initial(); }
}
