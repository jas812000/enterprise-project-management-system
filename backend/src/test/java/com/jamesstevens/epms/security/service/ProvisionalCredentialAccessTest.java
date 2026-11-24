package com.jamesstevens.epms.security.service;

import com.jamesstevens.epms.user.entity.UserCredential;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ProvisionalCredentialAccessTest {
    private UserCredential credential() {
        return new UserCredential(UUID.randomUUID(), "encoded", "ARGON2ID", false);
    }

    @Test void verifiedCredentialsRemainAllowed() {
        assertTrue(ProvisionalCredentialAccess.isAuthenticationAllowed(credential(), Instant.now()));
    }

    @Test void provisionalCredentialsAllowedBeforeButNotAtExpiration() {
        UserCredential credential = credential();
        OffsetDateTime issued = OffsetDateTime.of(2026, 11, 20, 0, 0, 0, 0, ZoneOffset.UTC);
        credential.issueProvisionalPassword(issued, new byte[28], "test-key", UUID.randomUUID());
        Instant expires = issued.plusHours(12).toInstant();
        assertTrue(ProvisionalCredentialAccess.isAuthenticationAllowed(credential, expires.minusNanos(1)));
        assertFalse(ProvisionalCredentialAccess.isAuthenticationAllowed(credential, expires));
        assertFalse(ProvisionalCredentialAccess.isAuthenticationAllowed(credential, expires.plusSeconds(1)));
    }

    @Test void expiredStateCannotAuthenticate() {
        UserCredential credential = credential();
        OffsetDateTime issued = OffsetDateTime.of(2026, 11, 20, 0, 0, 0, 0, ZoneOffset.UTC);
        credential.issueProvisionalPassword(issued, new byte[28], "test-key", UUID.randomUUID());
        assertTrue(credential.expireProvisionalPassword(issued.plusHours(12)));
        assertFalse(ProvisionalCredentialAccess.isAuthenticationAllowed(credential, issued.plusHours(13).toInstant()));
    }

    @Test void unknownStateFailsClosed() {
        UserCredential credential = org.mockito.Mockito.mock(UserCredential.class);
        org.mockito.Mockito.when(credential.getScreeningStatus()).thenReturn("UNKNOWN");
        assertFalse(ProvisionalCredentialAccess.isAuthenticationAllowed(credential, Instant.now()));
    }
}
