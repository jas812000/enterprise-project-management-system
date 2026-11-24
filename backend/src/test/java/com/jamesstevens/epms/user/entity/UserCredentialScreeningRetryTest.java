package com.jamesstevens.epms.user.entity;

import static org.junit.jupiter.api.Assertions.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserCredentialScreeningRetryTest {
    @Test void retryBackoffIsBoundedAndNeverExtendsExpiration() {
        UserCredential credential = new UserCredential(UUID.randomUUID(), "encoded", "ARGON2ID", false);
        OffsetDateTime issued = OffsetDateTime.of(2026, 11, 20, 12, 0, 0, 0, ZoneOffset.UTC);
        credential.issueProvisionalPassword(issued, new byte[28], "test-key", UUID.randomUUID());
        assertEquals(issued, credential.getNextScreeningRetryAt());
        long[] retryMinutes = {1, 2, 4, 8, 15, 30, 60, 120, 240};
        OffsetDateTime attemptTime = issued;
        for (int i = 0; i < retryMinutes.length; i++) {
            credential.deferScreeningRetry(attemptTime);
            attemptTime = attemptTime.plusMinutes(retryMinutes[i]);
            assertEquals(attemptTime, credential.getNextScreeningRetryAt());
            assertEquals(i + 1, credential.getScreeningRetryAttempts());
        }
        assertEquals(issued.plusHours(8), attemptTime);
        credential.deferScreeningRetry(attemptTime);
        assertEquals(9, credential.getScreeningRetryAttempts());
        assertEquals(issued.plusHours(12), credential.getNextScreeningRetryAt());
        assertEquals(issued.plusHours(12), credential.getProvisionalExpiresAt());
    }

    @Test void successfulResolutionClearsRetryEligibility() {
        UserCredential credential = new UserCredential(UUID.randomUUID(), "encoded", "ARGON2ID", false);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        credential.issueProvisionalPassword(now, new byte[28], "test-key", UUID.randomUUID());
        credential.deferScreeningRetry(now);
        assertTrue(credential.completeScreening(credential.getPasswordChangedAt(), false));
        assertNull(credential.getNextScreeningRetryAt());
    }
}
