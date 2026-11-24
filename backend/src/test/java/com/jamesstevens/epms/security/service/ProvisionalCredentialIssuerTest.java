package com.jamesstevens.epms.security.service;

import com.jamesstevens.epms.security.outage.PasswordScreeningOutageRecorder;
import com.jamesstevens.epms.user.entity.UserCredential;
import com.jamesstevens.epms.user.repository.UserCredentialRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProvisionalCredentialIssuerTest {
    private final UserCredentialRepository repository = mock(UserCredentialRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final Query advisoryQuery = mock(Query.class);
    private final PasswordScreeningOutageRecorder recorder = mock(PasswordScreeningOutageRecorder.class);
    private final DeferredScreeningCipher cipher = new DeferredScreeningCipher(new byte[32]);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-20T20:00:00Z"), ZoneOffset.UTC);
    private static final String PASSWORD = "LongSecurePassword123!";

    private ProvisionalCredentialIssuer issuer(String keyId) {
        ProvisionalCredentialIssuer service = new ProvisionalCredentialIssuer(repository, recorder, cipher, keyId, clock);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        when(entityManager.createNativeQuery(contains("pg_advisory_xact_lock"))).thenReturn(advisoryQuery);
        when(advisoryQuery.setParameter(eq("userId"), anyString())).thenReturn(advisoryQuery);
        return service;
    }

    @Test void issuesEncryptedProvisionalStateAfterRecordedOutage() throws Exception {
        UUID id = UUID.randomUUID();
        UUID outageId = UUID.randomUUID();
        UserCredential credential = new UserCredential(UUID.randomUUID(), "encoded-hash", "ARGON2ID", false);
        Field field = UserCredential.class.getDeclaredField("userCredentialId");
        field.setAccessible(true);
        field.set(credential, id);
        when(repository.saveAndFlush(credential)).thenReturn(credential);
        when(recorder.recordFailure("PROVIDER_TIMEOUT")).thenReturn(outageId);
        assertEquals(outageId, issuer("key-v1").issueAfterConfirmedOutage(credential, PASSWORD, "PROVIDER_TIMEOUT"));
        assertEquals("PENDING_VERIFICATION", credential.getScreeningStatus());
        assertEquals(outageId, credential.getScreeningOutageId());
        assertEquals("key-v1", credential.getScreeningDigestKeyId());
        assertEquals(credential.getProvisionalIssuedAt().plusHours(12), credential.getProvisionalExpiresAt());
        assertEquals(40, cipher.decrypt(credential.getScreeningDigestCiphertext(), id.toString()).length());
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(credential.getScreeningDigestCiphertext(), UUID.randomUUID().toString()));
        verify(repository, times(2)).saveAndFlush(credential);
        verify(advisoryQuery).setParameter("userId", credential.getUserId().toString());
        InOrder lockBeforeWrite = inOrder(advisoryQuery, repository);
        lockBeforeWrite.verify(advisoryQuery).getSingleResult();
        lockBeforeWrite.verify(repository, times(2)).saveAndFlush(credential);
        verify(recorder).recordFailure("PROVIDER_TIMEOUT");
    }

    @Test void missingEncryptionKeyIdFailsBeforeDatabaseWrites() {
        UserCredential credential = new UserCredential(UUID.randomUUID(), "encoded-hash", "ARGON2ID", false);
        assertThrows(IllegalStateException.class, () -> issuer(" ")
                .issueAfterConfirmedOutage(credential, PASSWORD, "PROVIDER_TIMEOUT"));
        verifyNoInteractions(repository, recorder);
    }

    @Test void invalidPasswordAndCategoryFailBeforeDatabaseWrites() {
        UserCredential credential = new UserCredential(UUID.randomUUID(), "encoded-hash", "ARGON2ID", false);
        assertThrows(IllegalArgumentException.class, () -> issuer("key-v1")
                .issueAfterConfirmedOutage(credential, "short", "PROVIDER_TIMEOUT"));
        assertThrows(IllegalArgumentException.class, () -> issuer("key-v1")
                .issueAfterConfirmedOutage(credential, PASSWORD, " "));
        verifyNoInteractions(repository, recorder);
        verify(advisoryQuery, never()).getSingleResult();
    }
}
