package com.jamesstevens.epms.user.entity;

import static org.junit.jupiter.api.Assertions.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserCredentialRetryScheduleTest {
    private static final long[] MINUTES = {1, 2, 4, 8, 15, 30, 60, 120, 240};

    @Test void nineApprovedRetriesAndNoTenthRetry() {
        OffsetDateTime issued = OffsetDateTime.of(2026, 11, 20, 12, 0, 0, 0, ZoneOffset.UTC);
        UserCredential credential = pending(issued);
        OffsetDateTime now = issued;
        for (int index = 0; index < MINUTES.length; index++) {
            credential.deferScreeningRetry(now);
            now = now.plusMinutes(MINUTES[index]);
            assertEquals(index + 1, credential.getScreeningRetryAttempts());
            assertEquals(now, credential.getNextScreeningRetryAt());
        }
        assertEquals(issued.plusHours(8), now);
        credential.deferScreeningRetry(now);
        assertEquals(9, credential.getScreeningRetryAttempts());
        assertEquals(issued.plusHours(12), credential.getNextScreeningRetryAt());
        credential.deferScreeningRetry(issued.plusHours(11));
        assertEquals(9, credential.getScreeningRetryAttempts());
        assertEquals(issued.plusHours(12), credential.getNextScreeningRetryAt());
    }

    @Test void lateFailuresNeverScheduleBeyondTwelveHourDeadline() {
        OffsetDateTime issued = OffsetDateTime.of(2026, 11, 20, 12, 0, 0, 0, ZoneOffset.UTC);
        UserCredential credential = pending(issued);
        credential.deferScreeningRetry(issued.plusHours(11).plusMinutes(59));
        assertEquals(issued.plusHours(12), credential.getNextScreeningRetryAt());
        assertThrows(IllegalStateException.class,
                () -> credential.deferScreeningRetry(issued.plusHours(12)));
    }

    @Test void onlyPendingCredentialsCanBeDeferred() {
        OffsetDateTime issued = OffsetDateTime.of(2026, 11, 20, 12, 0, 0, 0, ZoneOffset.UTC);
        UserCredential credential = pending(issued);
        assertTrue(credential.completeScreening(credential.getPasswordChangedAt(), false));
        assertThrows(IllegalStateException.class, () -> credential.deferScreeningRetry(issued));
    }

    private static UserCredential pending(OffsetDateTime issued) {
        UserCredential credential = new UserCredential(UUID.randomUUID(), "argon-hash", "ARGON2ID", false);
        credential.issueProvisionalPassword(issued, new byte[28], "test-key", UUID.randomUUID());
        return credential;
    }
}
