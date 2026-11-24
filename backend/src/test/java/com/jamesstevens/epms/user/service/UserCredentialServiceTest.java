package com.jamesstevens.epms.user.service;

import com.jamesstevens.epms.common.exception.ResourceConflictException;
import com.jamesstevens.epms.common.exception.ResourceNotFoundException;
import com.jamesstevens.epms.user.entity.UserCredential;
import com.jamesstevens.epms.user.repository.UserCredentialRepository;
import com.jamesstevens.epms.user.repository.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.jamesstevens.epms.security.config.PasswordEncoderConfig;
import com.jamesstevens.epms.security.service.PasswordScreeningDecisionService;
import com.jamesstevens.epms.security.service.ProvisionalCredentialIssuer;
import com.jamesstevens.epms.security.service.AuthenticationSecurityAuditService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserCredentialServiceTest {

    private UserRepository userRepository;
    private UserCredentialRepository credentialRepository;
    private PasswordEncoder passwordEncoder;
    private UserCredentialService credentialService;
    private EntityManager entityManager;
    private Query advisoryLockQuery;
    private UserCredentialFailureWriter failureWriter;
    private PasswordScreeningDecisionService screeningDecisions;
    private ProvisionalCredentialIssuer provisionalIssuer;
    private AuthenticationSecurityAuditService audit;

    @BeforeEach
    void setUp() {

        userRepository = mock(UserRepository.class);
        credentialRepository = mock(UserCredentialRepository.class);
        failureWriter = mock(UserCredentialFailureWriter.class);
        screeningDecisions = mock(PasswordScreeningDecisionService.class);
        provisionalIssuer = mock(ProvisionalCredentialIssuer.class);
        audit = mock(AuthenticationSecurityAuditService.class);
        when(screeningDecisions.decide(anyString()))
                .thenReturn(PasswordScreeningDecisionService.Decision.VERIFIED);

        passwordEncoder = new PasswordEncoderConfig().passwordEncoder();

        entityManager = mock(EntityManager.class);
        advisoryLockQuery = mock(Query.class);
        when(entityManager.createNativeQuery(contains("pg_advisory_xact_lock"))).thenReturn(advisoryLockQuery);
        when(advisoryLockQuery.setParameter(eq("userId"), anyString())).thenReturn(advisoryLockQuery);

        credentialService = new UserCredentialService(
                userRepository,
                credentialRepository,
                passwordEncoder,
                failureWriter,
                screeningDecisions,
                provisionalIssuer,
                audit
        );
        ReflectionTestUtils.setField(credentialService, "entityManager", entityManager);
    }

    @Test
    void credentialCreationAcquiresPerUserTransactionLockBeforeRepositoryAccess() {
        UUID userId = UUID.randomUUID();
        when(userRepository.existsById(userId)).thenReturn(false);
        assertThrows(ResourceNotFoundException.class,
                () -> credentialService.createCredential(userId, "SecurePassword123!", false));
        org.mockito.InOrder order = inOrder(entityManager, advisoryLockQuery, userRepository);
        order.verify(entityManager).createNativeQuery(contains("pg_advisory_xact_lock"));
        order.verify(advisoryLockQuery).setParameter("userId", userId.toString());
        order.verify(advisoryLockQuery).getSingleResult();
        order.verify(userRepository).existsById(userId);
    }

    @Test
    void createsCredentialWithEncodedPassword() {
        UUID userId = UUID.randomUUID();
        String rawPassword = "SecurePassword123!";

        when(userRepository.existsById(userId)).thenReturn(true);
        when(credentialRepository.findByUserId(userId))
                .thenReturn(Optional.empty());

        credentialService.createCredential(
                userId,
                rawPassword,
                true
        );

        ArgumentCaptor<UserCredential> captor =
                ArgumentCaptor.forClass(UserCredential.class);

        verify(credentialRepository).saveAndFlush(
                captor.capture()
        );

        UserCredential savedCredential = captor.getValue();

        assertEquals(userId, savedCredential.getUserId());
        assertEquals(
                "ARGON2ID",
                savedCredential.getPasswordAlgorithmCode()
        );
        assertTrue(savedCredential.isMustChangePassword());

        assertNotEquals(
                rawPassword,
                savedCredential.getPasswordHash()
        );

        assertTrue(
                passwordEncoder.matches(
                        rawPassword,
                        savedCredential.getPasswordHash()
                )
        );
    }

    @Test
    void rejectsCompromisedPasswordBeforePersistence() {
        UUID userId = UUID.randomUUID();
        when(userRepository.existsById(userId)).thenReturn(true);
        when(credentialRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(screeningDecisions.decide(anyString()))
                .thenReturn(PasswordScreeningDecisionService.Decision.REJECT_COMPROMISED);
        assertThrows(IllegalArgumentException.class,
                () -> credentialService.createCredential(userId, "SecurePassword123!", false));
        verify(credentialRepository, never()).saveAndFlush(any(UserCredential.class));
    }

    @Test
    void screeningOutageDoesNotSilentlyIssueVerifiedCredential() {
        UUID userId = UUID.randomUUID();
        when(userRepository.existsById(userId)).thenReturn(true);
        when(credentialRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(screeningDecisions.decide(anyString()))
                .thenReturn(PasswordScreeningDecisionService.Decision.PROVISIONAL_REQUIRED);
        credentialService.createCredential(userId, "SecurePassword123!", false);
        verify(provisionalIssuer).issueAfterConfirmedOutage(any(UserCredential.class),
                eq("SecurePassword123!"), eq("PROVIDER_UNAVAILABLE"));
        verify(credentialRepository, never()).saveAndFlush(any(UserCredential.class));
    }

    @Test
    void rejectsCompromisedPasswordChangeWithoutPersisting() {
        UUID userId = UUID.randomUUID();
        String current = "CurrentSecurePassword123!";
        UserCredential existing = new UserCredential(userId, passwordEncoder.encode(current),
                "ARGON2ID", false);
        when(credentialRepository.findByUserId(userId)).thenReturn(Optional.of(existing));
        when(screeningDecisions.decide(anyString()))
                .thenReturn(PasswordScreeningDecisionService.Decision.REJECT_COMPROMISED);
        assertThrows(IllegalArgumentException.class,
                () -> credentialService.changePassword(userId, current, "NewSecurePassword123!"));
        assertTrue(passwordEncoder.matches(current, existing.getPasswordHash()));
        verify(credentialRepository, never()).saveAndFlush(any(UserCredential.class));
    }

    @Test
    void screeningOutagePreservesExistingPassword() {
        UUID userId = UUID.randomUUID();
        String current = "CurrentSecurePassword123!";
        UserCredential existing = new UserCredential(userId, passwordEncoder.encode(current),
                "ARGON2ID", false);
        when(credentialRepository.findByUserId(userId)).thenReturn(Optional.of(existing));
        when(screeningDecisions.decide(anyString()))
                .thenReturn(PasswordScreeningDecisionService.Decision.PROVISIONAL_REQUIRED);
        credentialService.changePassword(userId, current, "NewSecurePassword123!");
        assertTrue(passwordEncoder.matches("NewSecurePassword123!", existing.getPasswordHash()));
        verify(provisionalIssuer).issueAfterConfirmedOutage(eq(existing),
                eq("NewSecurePassword123!"), eq("PROVIDER_UNAVAILABLE"));
        verify(credentialRepository, never()).saveAndFlush(any(UserCredential.class));
    }

    @Test
    void rejectsCredentialCreationForMissingUser() {
        UUID userId = UUID.randomUUID();

        when(userRepository.existsById(userId)).thenReturn(false);

        assertThrows(
                ResourceNotFoundException.class,
                () -> credentialService.createCredential(
                        userId,
                        "SecurePassword123!",
                        false
                )
        );

        verify(credentialRepository, never())
                .saveAndFlush(any(UserCredential.class));
    }

    @Test
    void rejectsDuplicateCredentialCreation() {
        UUID userId = UUID.randomUUID();

        when(userRepository.existsById(userId)).thenReturn(true);

        when(credentialRepository.findByUserId(userId))
                .thenReturn(Optional.of(
                        new UserCredential(
                                userId,
                                "existing-encoded-hash",
                                "ARGON2ID",
                                false
                        )
                ));

        assertThrows(
                ResourceConflictException.class,
                () -> credentialService.createCredential(
                        userId,
                        "SecurePassword123!",
                        false
                )
        );

        verify(credentialRepository, never())
                .saveAndFlush(any(UserCredential.class));
    }

    @Test
    void rejectsBlankPassword() {
        UUID userId = UUID.randomUUID();

        assertThrows(
                IllegalArgumentException.class,
                () -> credentialService.createCredential(
                        userId,
                        "   ",
                        false
                )
        );

        verifyNoInteractions(
                userRepository,
                credentialRepository
        );
    }

    @Test
    void rejectsNullUserId() {
        assertThrows(
                NullPointerException.class,
                () -> credentialService.createCredential(
                        null,
                        "SecurePassword123!",
                        false
                )
        );

        verifyNoInteractions(
                userRepository,
                credentialRepository
        );
    }


    @Test
    void verifiesCorrectPassword() {
        UUID userId = UUID.randomUUID();
        String rawPassword = "SecurePassword123!";

        String passwordHash = passwordEncoder.encode(rawPassword);

        when(credentialRepository.findByUserId(userId))
                .thenReturn(Optional.of(
                        new UserCredential(
                                userId,
                                passwordHash,
                                "ARGON2ID",
                                false
                        )
                ));

        assertTrue(
                credentialService.verifyPassword(
                        userId,
                        rawPassword
                )
        );
    }

    @Test
    void rejectsIncorrectPassword() {
        UUID userId = UUID.randomUUID();

        String passwordHash =
                passwordEncoder.encode("CorrectPassword123!");

        when(credentialRepository.findByUserId(userId))
                .thenReturn(Optional.of(
                        new UserCredential(
                                userId,
                                passwordHash,
                                "ARGON2ID",
                                false
                        )
                ));

        assertFalse(
                credentialService.verifyPassword(
                        userId,
                        "IncorrectPassword123!"
                )
        );
    }

    @Test
    void rejectsPasswordWhenCredentialDoesNotExist() {
        UUID userId = UUID.randomUUID();

        when(credentialRepository.findByUserId(userId))
                .thenReturn(Optional.empty());

        assertFalse(
                credentialService.verifyPassword(
                        userId,
                        "SecurePassword123!"
                )
        );
    }

    @Test
    void rejectsBlankPasswordDuringVerification() {
        UUID userId = UUID.randomUUID();

        assertFalse(
                credentialService.verifyPassword(
                        userId,
                        "   "
                )
        );

        verifyNoInteractions(credentialRepository);
    }

    @Test
    void rejectsUnsupportedPasswordAlgorithm() {
        UUID userId = UUID.randomUUID();

        when(credentialRepository.findByUserId(userId))
                .thenReturn(Optional.of(
                        new UserCredential(
                                userId,
                                "legacy-password-hash",
                                "LEGACY",
                                false
                        )
                ));

        assertFalse(
                credentialService.verifyPassword(
                        userId,
                        "SecurePassword123!"
                )
        );
    }

    @Test
    void rejectsNullUserIdDuringVerification() {
        assertThrows(
                NullPointerException.class,
                () -> credentialService.verifyPassword(
                        null,
                        "SecurePassword123!"
                )
        );

        verifyNoInteractions(credentialRepository);
    }


    @Test
    void recordsFailedAuthenticationAttempt() {

        UUID userId = UUID.randomUUID();

        credentialService.recordFailedAuthenticationAttempt(userId);

        verify(failureWriter).recordFailedAttempt(userId);
    }

    @Test
    void recordsRepeatedFailedAuthenticationAttempts() {

        UUID userId = UUID.randomUUID();

        credentialService.recordFailedAuthenticationAttempt(userId);
        credentialService.recordFailedAuthenticationAttempt(userId);
        credentialService.recordFailedAuthenticationAttempt(userId);

        verify(failureWriter, times(3))
                .recordFailedAttempt(userId);
    }

    @Test
    void rejectsFailedAttemptWhenCredentialDoesNotExist() {

        UUID userId = UUID.randomUUID();

        doThrow(new ResourceNotFoundException(
                "User credentials not found"
        )).when(failureWriter).recordFailedAttempt(userId);

        assertThrows(
                ResourceNotFoundException.class,
                () -> credentialService
                        .recordFailedAuthenticationAttempt(userId)
        );

        verify(failureWriter).recordFailedAttempt(userId);
    }

    @Test
    void rejectsNullUserIdWhenRecordingFailedAttempt() {

        assertThrows(
                NullPointerException.class,
                () -> credentialService
                        .recordFailedAuthenticationAttempt(null)
        );

        verifyNoInteractions(failureWriter);
    }

    @Test
    void retriesAfterOneOptimisticLockConflict() {

        UUID userId = UUID.randomUUID();

        doThrow(new OptimisticLockingFailureException(
                "Simulated conflict"
        ) {})
                .doNothing()
                .when(failureWriter)
                .recordFailedAttempt(userId);

        credentialService.recordFailedAuthenticationAttempt(userId);

        verify(failureWriter, times(2))
                .recordFailedAttempt(userId);
    }

    @Test
    void retriesAfterTwoOptimisticLockConflicts() {

        UUID userId = UUID.randomUUID();

        doThrow(new OptimisticLockingFailureException(
                "First conflict"
        ) {})
                .doThrow(new OptimisticLockingFailureException(
                        "Second conflict"
                ) {})
                .doNothing()
                .when(failureWriter)
                .recordFailedAttempt(userId);

        credentialService.recordFailedAuthenticationAttempt(userId);

        verify(failureWriter, times(3))
                .recordFailedAttempt(userId);
    }

    @Test
    void stopsRetryingAfterMaximumAttempts() {

        UUID userId = UUID.randomUUID();

        doThrow(new OptimisticLockingFailureException(
                "Persistent conflict"
        ) {})
                .when(failureWriter)
                .recordFailedAttempt(userId);

        assertThrows(
                OptimisticLockingFailureException.class,
                () -> credentialService
                        .recordFailedAuthenticationAttempt(userId)
        );

        verify(failureWriter, times(3))
                .recordFailedAttempt(userId);
    }

    @Test
    void doesNotRetryUnrelatedDatabaseFailures() {

        UUID userId = UUID.randomUUID();

        doThrow(new IllegalStateException(
                "Unexpected persistence failure"
        )).when(failureWriter).recordFailedAttempt(userId);

        assertThrows(
                IllegalStateException.class,
                () -> credentialService
                        .recordFailedAuthenticationAttempt(userId)
        );

        verify(failureWriter).recordFailedAttempt(userId);
    }

}
