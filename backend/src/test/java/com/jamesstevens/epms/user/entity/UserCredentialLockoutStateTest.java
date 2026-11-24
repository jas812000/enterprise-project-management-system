package com.jamesstevens.epms.user.entity;

import com.jamesstevens.epms.security.service.ProgressiveLoginLockoutPolicy;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class UserCredentialLockoutStateTest {
    @Test void mapsProgressiveStateWithoutChangingBusinessAccountStatus() {
        var credential = new UserCredential(UUID.randomUUID(), "encoded", "ARGON2ID", false);
        var at = Instant.parse("2026-11-20T12:05:00Z");
        var state = new ProgressiveLoginLockoutPolicy.State(2, 1, at, false);
        credential.applyLoginLockoutState(state);
        assertEquals(state, credential.getLoginLockoutState());
        assertEquals(0, credential.getFailedAttemptCount());
    }
    @Test void administratorLockIsRepresentedIndependently() {
        var credential = new UserCredential(UUID.randomUUID(), "encoded", "ARGON2ID", false);
        var state = new ProgressiveLoginLockoutPolicy.State(3, 2, null, true);
        credential.applyLoginLockoutState(state);
        assertTrue(credential.getLoginLockoutState().administratorUnlockRequired());
        assertEquals(ProgressiveLoginLockoutPolicy.initial(),
                ProgressiveLoginLockoutPolicy.administratorUnlock());
    }
}
