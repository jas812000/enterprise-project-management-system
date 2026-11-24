package com.jamesstevens.epms.user.entity;

import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class UserCredentialScreeningResetTest {
    @Test
    void replacementPasswordClearsStaleProvisionalMetadata() {
        var credential = new UserCredential(UUID.randomUUID(), "encoded-old", "ARGON2ID", false);
        var issuedAt = OffsetDateTime.now(ZoneOffset.UTC);
        credential.issueProvisionalPassword(issuedAt, new byte[32], "key-v1", UUID.randomUUID());
        credential.changePassword("encoded-new", "ARGON2ID");
        assertEquals("VERIFIED", credential.getScreeningStatus());
        assertNull(credential.getProvisionalIssuedAt());
        assertNull(credential.getProvisionalExpiresAt());
        assertNull(credential.getScreeningDigestCiphertext());
        assertNull(credential.getScreeningDigestKeyId());
        assertNull(credential.getScreeningOutageId());
        assertFalse(credential.completeScreening(credential.getPasswordChangedAt(), true));
    }
}
