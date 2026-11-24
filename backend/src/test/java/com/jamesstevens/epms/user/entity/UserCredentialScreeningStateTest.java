package com.jamesstevens.epms.user.entity;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UserCredentialScreeningStateTest {
    private static final OffsetDateTime ISSUED =
            OffsetDateTime.of(2026, 11, 20, 12, 0, 0, 0, ZoneOffset.UTC);

    private UserCredential credential() {
        return new UserCredential(UUID.randomUUID(), "encoded", "ARGON2ID", false);
    }

    private void provisional(UserCredential credential) {
        credential.issueProvisionalPassword(ISSUED, new byte[32], "key-v1", UUID.randomUUID());
    }

    @Test void issuanceCreatesIndependentTwelveHourWindow() {
        UserCredential credential = credential();
        provisional(credential);
        assertEquals("PENDING_VERIFICATION", credential.getScreeningStatus());
        assertEquals(ISSUED.plusHours(12), credential.getProvisionalExpiresAt());
        assertNotNull(credential.getScreeningDigestCiphertext());
    }

    @Test void expirationAtExactBoundaryRequiresChangeAndClearsMaterial() {
        UserCredential credential = credential();
        provisional(credential);
        assertFalse(credential.expireProvisionalPassword(ISSUED.plusHours(12).minusNanos(1)));
        assertTrue(credential.expireProvisionalPassword(ISSUED.plusHours(12)));
        assertEquals("EXPIRED", credential.getScreeningStatus());
        assertTrue(credential.isMustChangePassword());
        assertNull(credential.getScreeningDigestCiphertext());
        assertFalse(credential.expireProvisionalPassword(ISSUED.plusHours(13)));
    }

    @Test void staleScreeningCannotChangeReplacementCredential() {
        UserCredential credential = credential();
        provisional(credential);
        assertFalse(credential.completeScreening(ISSUED, false));
        assertEquals("PENDING_VERIFICATION", credential.getScreeningStatus());
    }

    @Test void missingEncryptionMetadataIsRejected() {
        UserCredential credential = credential();
        assertThrows(IllegalArgumentException.class,
                () -> credential.issueProvisionalPassword(ISSUED, new byte[0], "key-v1", UUID.randomUUID()));
        assertEquals("VERIFIED", credential.getScreeningStatus());
    }

    @Test void ciphertextIsDefensivelyCopied() {
        UserCredential credential = credential();
        byte[] material = new byte[32];
        material[0] = 5;
        credential.issueProvisionalPassword(ISSUED, material, "key-v1", UUID.randomUUID());
        material[0] = 6;
        byte[] returned = credential.getScreeningDigestCiphertext();
        assertEquals(5, returned[0]);
        returned[0] = 9;
        assertEquals(5, credential.getScreeningDigestCiphertext()[0]);
    }
}
