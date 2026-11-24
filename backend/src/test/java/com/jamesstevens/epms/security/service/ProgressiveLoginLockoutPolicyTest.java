package com.jamesstevens.epms.security.service;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class ProgressiveLoginLockoutPolicyTest {
    @Test void progressiveThresholdsAndAdministratorLock() {
        Instant now = Instant.parse("2026-11-20T00:00:00Z");
        var state = ProgressiveLoginLockoutPolicy.initial();
        int[] limits = {5, 4, 3, 2};
        int[] delays = {5, 10, 15};
        for (int stage = 0; stage < limits.length; stage++) {
            for (int attempt = 1; attempt <= limits[stage]; attempt++) {
                state = ProgressiveLoginLockoutPolicy.failure(state, now);
                if (attempt < limits[stage]) assertFalse(ProgressiveLoginLockoutPolicy.isBlocked(state, now));
            }
            assertTrue(ProgressiveLoginLockoutPolicy.isBlocked(state, now));
            Instant blockedCheckAt = now;
            assertThrows(IllegalStateException.class,
                    () -> ProgressiveLoginLockoutPolicy.failure(
                            new ProgressiveLoginLockoutPolicy.State(3, 2, null, true), blockedCheckAt));
            if (stage < 3) {
                assertEquals(now.plus(Duration.ofMinutes(delays[stage])), state.lockedUntil());
                now = now.plus(Duration.ofMinutes(delays[stage]));
                assertFalse(ProgressiveLoginLockoutPolicy.isBlocked(state, now));
            } else assertTrue(state.administratorUnlockRequired());
        }
        assertEquals(2, state.failuresInStage());
        assertFalse(ProgressiveLoginLockoutPolicy.isBlocked(
                ProgressiveLoginLockoutPolicy.administratorUnlock(), now));
    }
    @Test void successResetsEscalationAndTemporaryExpiryDoesNot() {
        Instant now = Instant.parse("2026-11-20T00:00:00Z");
        var state = ProgressiveLoginLockoutPolicy.initial();
        for (int i=0; i<5; i++) state = ProgressiveLoginLockoutPolicy.failure(state, now);
        assertEquals(1, state.stage());
        assertEquals(1, ProgressiveLoginLockoutPolicy.failure(state, now.plusSeconds(300)).failuresInStage());
        assertEquals(0, ProgressiveLoginLockoutPolicy.successfulAuthentication(state, now.plusSeconds(300)).stage());
    }
}
