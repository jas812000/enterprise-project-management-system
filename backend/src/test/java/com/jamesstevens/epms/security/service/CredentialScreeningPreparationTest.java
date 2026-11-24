package com.jamesstevens.epms.security.service;

import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CredentialScreeningPreparationTest {
    private static final String PASSWORD = "LongSecurePassword123!";
    private static final String ID = "credential-123";
    private final DeferredScreeningCipher cipher = new DeferredScreeningCipher(new byte[32]);

    private CredentialScreeningPreparation prepare(PwnedPasswordScreeningService.RangeTransport transport) {
        return new CredentialScreeningPreparation(
                new PasswordScreeningDecisionService(new PwnedPasswordScreeningService(transport)),
                cipher, "key-v1");
    }

    @Test void safePasswordDoesNotRetainDigest() {
        var result = prepare(prefix -> "").prepare(PASSWORD, ID, null, OffsetDateTime.now(ZoneOffset.UTC));
        assertEquals(CredentialScreeningPreparation.Status.VERIFIED, result.status());
        assertNull(result.encryptedDigest());
        assertNull(result.expiresAt());
    }

    @Test void outageProducesEncryptedDigestAndFixedDeadline() {
        var service = prepare(prefix -> { throw new IllegalStateException("offline"); });
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var outage = UUID.randomUUID();
        var result = service.prepare(PASSWORD, ID, outage, now);
        assertEquals(CredentialScreeningPreparation.Status.PROVISIONAL, result.status());
        assertEquals(now.plusHours(12), result.expiresAt());
        assertEquals(outage, result.outageId());
        assertEquals(40, cipher.decrypt(result.encryptedDigest(), ID).length());
        assertNotEquals(PASSWORD, cipher.decrypt(result.encryptedDigest(), ID));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(result.encryptedDigest(), "other"));
        byte[] exposed = result.encryptedDigest();
        exposed[0] ^= 1;
        assertNotEquals(exposed[0], result.encryptedDigest()[0]);
    }

    @Test void outageWithoutPersistedIncidentFailsClosed() {
        assertThrows(NullPointerException.class, () ->
                prepare(prefix -> { throw new IllegalStateException("offline"); })
                        .prepare(PASSWORD, ID, null, OffsetDateTime.now(ZoneOffset.UTC)));
    }

    @Test void compromisedPasswordIsRejected() {
        var password = PASSWORD;
        assertThrows(IllegalArgumentException.class, () ->
                prepare(prefix -> "D8CC1D9D121538F823171F21265B8565B6B:12\n")
                        .prepare(password, ID, UUID.randomUUID(), OffsetDateTime.now(ZoneOffset.UTC)));
    }

    @Test void invalidPasswordDoesNotContactProvider() {
        assertThrows(IllegalArgumentException.class, () ->
                prepare(prefix -> { fail("must not call provider"); return ""; })
                        .prepare("short", ID, UUID.randomUUID(), OffsetDateTime.now(ZoneOffset.UTC)));
    }
}
