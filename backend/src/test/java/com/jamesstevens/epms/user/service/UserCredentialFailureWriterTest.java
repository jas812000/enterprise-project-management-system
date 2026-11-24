
package com.jamesstevens.epms.user.service;

import com.jamesstevens.epms.common.exception.ResourceNotFoundException;
import com.jamesstevens.epms.user.entity.UserCredential;
import com.jamesstevens.epms.user.repository.UserCredentialRepository;

import org.junit.jupiter.api.BeforeEach;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserCredentialFailureWriterTest {

    private UserCredentialRepository credentialRepository;
    private UserCredentialFailureWriter failureWriter;
    private EntityManager entityManager;
    private Query lockQuery;

    @BeforeEach
    void setUp() {

        credentialRepository =
                mock(UserCredentialRepository.class);

        failureWriter =
                new UserCredentialFailureWriter(credentialRepository);
        entityManager = mock(EntityManager.class);
        lockQuery = mock(Query.class);
        when(entityManager.createNativeQuery(contains("pg_advisory_xact_lock"))).thenReturn(lockQuery);
        when(lockQuery.setParameter(eq("userId"), anyString())).thenReturn(lockQuery);
        ReflectionTestUtils.setField(failureWriter, "entityManager", entityManager);
    }

    @Test
    void acquiresTransactionAdvisoryLockBeforeCredentialRowLock() {
        UUID userId = UUID.randomUUID();
        UserCredential credential = new UserCredential(userId, "encoded", "ARGON2ID", false);
        when(credentialRepository.findByUserIdForUpdate(userId))
                .thenReturn(Optional.of(credential));
        failureWriter.recordFailedAttempt(userId);
        var ordered = inOrder(entityManager, lockQuery, credentialRepository);
        ordered.verify(entityManager).createNativeQuery(contains("pg_advisory_xact_lock"));
        ordered.verify(lockQuery).setParameter("userId", userId.toString());
        ordered.verify(lockQuery).getSingleResult();
        ordered.verify(credentialRepository).findByUserIdForUpdate(userId);
        ordered.verify(credentialRepository).saveAndFlush(credential);
    }

    @Test
    void recordsFailedAttemptAndUpdatesTimestamp() {

        UUID userId = UUID.randomUUID();

        UserCredential credential = new UserCredential(
                userId,
                "encoded-test-hash",
                "ARGON2ID",
                false
        );

        when(credentialRepository.findByUserIdForUpdate(userId))
                .thenReturn(Optional.of(credential));

        failureWriter.recordFailedAttempt(userId);

        assertEquals(1, credential.getFailedAttemptCount());
        assertNotNull(credential.getLastFailedAttemptAt());

        verify(credentialRepository).findByUserIdForUpdate(userId);
        verify(credentialRepository).saveAndFlush(credential);
    }

    @Test
    void incrementsFailureCounterOnRepeatedWrites() {

        UUID userId = UUID.randomUUID();

        UserCredential credential = new UserCredential(
                userId,
                "encoded-test-hash",
                "ARGON2ID",
                false
        );

        when(credentialRepository.findByUserIdForUpdate(userId))
                .thenReturn(Optional.of(credential));

        failureWriter.recordFailedAttempt(userId);
        failureWriter.recordFailedAttempt(userId);

        assertEquals(2, credential.getFailedAttemptCount());
        assertNotNull(credential.getLastFailedAttemptAt());

        verify(credentialRepository, times(2))
                .saveAndFlush(credential);
    }

    @Test
    void rejectsMissingCredentialWithoutSaving() {

        UUID userId = UUID.randomUUID();

        when(credentialRepository.findByUserIdForUpdate(userId))
                .thenReturn(Optional.empty());

        assertThrows(
                ResourceNotFoundException.class,
                () -> failureWriter.recordFailedAttempt(userId)
        );

        verify(credentialRepository, never())
                .saveAndFlush(any(UserCredential.class));
    }

    @Test
    void rejectsNullUserIdWithoutRepositoryAccess() {

        assertThrows(
                NullPointerException.class,
                () -> failureWriter.recordFailedAttempt(null)
        );

        verifyNoInteractions(credentialRepository);
    }
}
