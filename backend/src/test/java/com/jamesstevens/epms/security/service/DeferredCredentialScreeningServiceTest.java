package com.jamesstevens.epms.security.service;

import com.jamesstevens.epms.user.entity.UserCredential;
import com.jamesstevens.epms.user.repository.UserCredentialRepository;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionCallback;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeferredCredentialScreeningServiceTest {
    private final UserCredentialRepository repository = mock(UserCredentialRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void executeCallbacks() {
        when(transactions.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback) invocation.getArgument(0)).doInTransaction(null));
    }
    private final PwnedPasswordScreeningService.RangeTransport transport = mock(PwnedPasswordScreeningService.RangeTransport.class);
    private final DeferredScreeningCipher cipher = new DeferredScreeningCipher(new byte[32]);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-20T20:00:00Z"), ZoneOffset.UTC);
    private final UUID id = UUID.randomUUID();

    private DeferredCredentialScreeningService service() {
        executeCallbacks();
        return new DeferredCredentialScreeningService(repository,
                new PwnedPasswordScreeningService(transport), jdbc, cipher, "test-key", clock, transactions);
    }

    private UserCredential pending() throws Exception {
        UserCredential credential = new UserCredential(UUID.randomUUID(), "argon-hash", "ARGON2ID", false);
        Field field = UserCredential.class.getDeclaredField("userCredentialId");
        field.setAccessible(true);
        field.set(credential, id);
        credential.issueProvisionalPassword(OffsetDateTime.now(clock),
                cipher.encrypt("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", id.toString()),
                "test-key", UUID.randomUUID());
        when(repository.findById(id)).thenReturn(Optional.of(credential));
        when(repository.findByUserIdForUpdate(credential.getUserId())).thenReturn(Optional.of(credential));
        return credential;
    }

    @Test void cleanResultVerifiesAndClearsCiphertext() throws Exception {
        UserCredential credential = pending();
        when(transport.fetch("AAAAA")).thenReturn("");
        assertEquals(DeferredCredentialScreeningService.Outcome.VERIFIED, service().resolve(id));
        assertEquals("VERIFIED", credential.getScreeningStatus());
        assertNull(credential.getScreeningDigestCiphertext());
        verify(repository).saveAndFlush(credential);
        verify(jdbc).update(contains("password_security_events"), any(), any(), any(), eq("PASSWORD_VERIFIED"));
    }

    @Test void breachedResultRequiresChangeAndClearsCiphertext() throws Exception {
        UserCredential credential = pending();
        when(transport.fetch("AAAAA")).thenReturn("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA:5");
        assertEquals(DeferredCredentialScreeningService.Outcome.CHANGE_REQUIRED, service().resolve(id));
        assertTrue(credential.isMustChangePassword());
        assertNull(credential.getScreeningDigestCiphertext());
        verify(jdbc).update(contains("password_security_events"), any(), any(), any(), eq("PASSWORD_COMPROMISED"));
    }

    @Test void outageLeavesPendingStateUntouched() throws Exception {
        UserCredential credential = pending();
        when(transport.fetch("AAAAA")).thenThrow(new IllegalStateException("provider down"));
        assertEquals(DeferredCredentialScreeningService.Outcome.UNAVAILABLE, service().resolve(id));
        assertEquals("PENDING_VERIFICATION", credential.getScreeningStatus());
        assertEquals(1, credential.getScreeningRetryAttempts());
        assertEquals(OffsetDateTime.now(clock).plusMinutes(1), credential.getNextScreeningRetryAt());
        verify(repository).saveAndFlush(credential);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test void mismatchedKeyFailsClosed() throws Exception {
        UserCredential credential = pending();
        credential.issueProvisionalPassword(OffsetDateTime.now(clock),
                cipher.encrypt("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", id.toString()),
                "other-key", UUID.randomUUID());
        assertThrows(IllegalStateException.class, () -> service().resolve(id));
        verifyNoInteractions(transport, jdbc);
    }

    @Test void alreadyVerifiedSkipsProviderAndWrites() throws Exception {
        UserCredential credential = pending();
        credential.completeScreening(credential.getPasswordChangedAt(), false);
        assertEquals(DeferredCredentialScreeningService.Outcome.NOT_PENDING, service().resolve(id));
        verifyNoInteractions(transport, jdbc);
    }
}
