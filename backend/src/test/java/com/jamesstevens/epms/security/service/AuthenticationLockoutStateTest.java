package com.jamesstevens.epms.security.service;

import com.jamesstevens.epms.user.entity.UserCredential;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class AuthenticationLockoutStateTest {
    @Test void fourEscalationsAndAdministratorRecovery() {
        var c = new UserCredential(java.util.UUID.randomUUID(), "encoded", "ARGON2ID", false);
        Instant t = Instant.parse("2026-11-20T12:00:00Z");
        int[] thresholds = {5, 4, 3, 2};
        for (int stage = 0; stage < thresholds.length; stage++) {
            for (int i = 0; i < thresholds[stage]; i++) c.registerLoginFailure(t);
            assertTrue(ProgressiveLoginLockoutPolicy.isBlocked(c.getLoginLockoutState(), t));
            if (stage < 3) {
                var before = c.getLoginLockoutState();
                c.registerLoginFailure(t);
                assertEquals(before, c.getLoginLockoutState());
                t = t.plusSeconds((stage + 1) * 300L + 1);
            }
        }
        assertTrue(c.getLoginLockoutState().administratorUnlockRequired());
        c.administratorUnlockLogin();
        assertEquals(ProgressiveLoginLockoutPolicy.initial(), c.getLoginLockoutState());
        assertEquals(0, c.getFailedAttemptCount());
    }
    @Test void successResetsEscalation() {
        var c = new UserCredential(java.util.UUID.randomUUID(), "encoded", "ARGON2ID", false);
        var now = Instant.parse("2026-11-20T12:00:00Z");
        c.registerLoginFailure(now);
        c.resetLoginLockoutAfterSuccess(now);
        assertEquals(ProgressiveLoginLockoutPolicy.initial(), c.getLoginLockoutState());
    }
}
