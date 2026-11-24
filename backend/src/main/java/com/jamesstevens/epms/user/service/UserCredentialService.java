package com.jamesstevens.epms.user.service;

import com.jamesstevens.epms.common.exception.ResourceConflictException;
import com.jamesstevens.epms.common.exception.ResourceNotFoundException;
import com.jamesstevens.epms.user.entity.UserCredential;
import com.jamesstevens.epms.user.repository.UserCredentialRepository;
import com.jamesstevens.epms.user.repository.UserRepository;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.OptimisticLockingFailureException;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.util.Objects;
import com.jamesstevens.epms.security.service.PasswordPolicy;
import com.jamesstevens.epms.security.service.PasswordScreeningDecisionService;
import com.jamesstevens.epms.security.service.ProvisionalCredentialIssuer;
import com.jamesstevens.epms.security.service.AuthenticationSecurityAuditService;
import java.util.UUID;

/**
 * Manages password credentials for existing EPMS users.
 *<p>
 * Plaintext passwords are encoded before persistence.
 * Password hashes must not be exposed through user DTOs.
 */
@Service
public class UserCredentialService {

    @PersistenceContext
    private EntityManager entityManager;

    private static final String PASSWORD_ALGORITHM = "ARGON2ID";
    private static final String USER_ID_REQUIRED = "User ID is required";

    private final UserRepository userRepository;
    private final UserCredentialRepository credentialRepository;
    private final PasswordEncoder passwordEncoder;
    private static final int MAX_FAILED_ATTEMPT_WRITES = 3;

    private final UserCredentialFailureWriter failureWriter;
    private final PasswordScreeningDecisionService screeningDecisions;
    private final ProvisionalCredentialIssuer provisionalIssuer;
    private final AuthenticationSecurityAuditService audit;

    public UserCredentialService(
            UserRepository userRepository,
            UserCredentialRepository credentialRepository,
            PasswordEncoder passwordEncoder,
            UserCredentialFailureWriter failureWriter,
            PasswordScreeningDecisionService screeningDecisions,
            ProvisionalCredentialIssuer provisionalIssuer,
            AuthenticationSecurityAuditService audit) {

        this.userRepository =
                Objects.requireNonNull(userRepository);

        this.credentialRepository =
                Objects.requireNonNull(credentialRepository);

        this.passwordEncoder =
                Objects.requireNonNull(passwordEncoder);

        this.failureWriter =
                Objects.requireNonNull(failureWriter);
        this.screeningDecisions = Objects.requireNonNull(screeningDecisions);
        this.provisionalIssuer = Objects.requireNonNull(provisionalIssuer);
        this.audit = Objects.requireNonNull(audit);
    }

    /**
     * Creates a password credential for an existing user.
     *
     * @param userId existing user identifier
     * @param rawPassword plaintext password supplied securely
     * @param mustChangePassword whether a change is required
     */
    @Transactional
    public void createCredential(
            UUID userId,
            String rawPassword,
            boolean mustChangePassword) {

        Objects.requireNonNull(userId, USER_ID_REQUIRED);

        // Match the token-issuance transaction lock before any credential-state reads.
        // Held until transaction completion; protects the credential-creation path.
        entityManager.createNativeQuery(
                "SELECT pg_catalog.pg_advisory_xact_lock(" +
                "pg_catalog.hashtextextended(CAST(:userId AS text), 82302026))")
                .setParameter("userId", userId.toString())
                .getSingleResult();

        String normalizedPassword = PasswordPolicy.normalizeAndValidate(rawPassword);

        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException(
                    "User not found"
            );
        }

        if (credentialRepository.findByUserId(userId).isPresent()) {
            throw new ResourceConflictException(
                    "User already has password credentials"
            );
        }

        PasswordScreeningDecisionService.Decision screening = screeningDecision(normalizedPassword);
        String passwordHash = passwordEncoder.encode(normalizedPassword);

        UserCredential credential = new UserCredential(
                userId,
                passwordHash,
                PASSWORD_ALGORITHM,
                mustChangePassword
        );

        if (screening == PasswordScreeningDecisionService.Decision.PROVISIONAL_REQUIRED) {
            provisionalIssuer.issueAfterConfirmedOutage(credential, normalizedPassword, "PROVIDER_UNAVAILABLE");
        } else {
            credentialRepository.saveAndFlush(credential);
        }
    }

    /**
     * Verifies a supplied password against the stored credential.
     *
     * @param userId existing user identifier
     * @param rawPassword plaintext password supplied for verification
     * @return true when the password matches; otherwise false
     */
    @Transactional(readOnly = true)
    public boolean verifyPassword(UUID userId, String rawPassword) {

        Objects.requireNonNull(userId, USER_ID_REQUIRED);

        boolean matches = false;
        if (rawPassword != null && !rawPassword.isBlank()) {
            String normalizedPassword = PasswordPolicy.normalizeForVerification(rawPassword);
            matches = credentialRepository.findByUserId(userId)
                    .filter(credential ->
                            PASSWORD_ALGORITHM.equals(credential.getPasswordAlgorithmCode()))
                    .map(credential -> passwordEncoder.matches(
                            normalizedPassword, credential.getPasswordHash()))
                    .orElse(false);
        }
        return matches;
    }

    /** Changes a password after verifying the current password and reuse policy. */
    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        Objects.requireNonNull(userId, USER_ID_REQUIRED);
        // Must precede credential reads and remain held until transaction completion.
        // The seed and UUID-text encoding match the protected issuance routine.
        entityManager.createNativeQuery(
                "SELECT pg_catalog.pg_advisory_xact_lock(" +
                "pg_catalog.hashtextextended(CAST(:userId AS text), 82302026))")
                .setParameter("userId", userId.toString())
                .getSingleResult();
        String normalizedNew = PasswordPolicy.normalizeAndValidate(newPassword);
        String normalizedCurrent = PasswordPolicy.normalizeForVerification(currentPassword);
        UserCredential credential = credentialRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User credentials not found"));
        if (!PASSWORD_ALGORITHM.equals(credential.getPasswordAlgorithmCode())
                || normalizedCurrent == null
                || !passwordEncoder.matches(normalizedCurrent, credential.getPasswordHash())) {
            throw new IllegalArgumentException("Current password is incorrect");
        }
        if (passwordEncoder.matches(normalizedNew, credential.getPasswordHash())) {
            throw new IllegalArgumentException("New password must differ from current password");
        }
        PasswordScreeningDecisionService.Decision screening = screeningDecision(normalizedNew);
        credential.changePassword(passwordEncoder.encode(normalizedNew), PASSWORD_ALGORITHM);
        if (screening == PasswordScreeningDecisionService.Decision.PROVISIONAL_REQUIRED) {
            provisionalIssuer.issueAfterConfirmedOutage(credential, normalizedNew, "PROVIDER_UNAVAILABLE");
        } else {
            credentialRepository.saveAndFlush(credential);
        }
        // Mandatory: same transaction as the credential change; never REQUIRES_NEW.
        // A failed audit INSERT aborts the password change, including provisional state.
        audit.event("PASSWORD_CHANGED", userId, userId, null, null,
                0, 0, null, "ACCEPTED");
    }

    /** Compromised passwords fail before encoding or persistence; outages are explicitly classified. */
    private PasswordScreeningDecisionService.Decision screeningDecision(String normalizedPassword) {
        PasswordScreeningDecisionService.Decision decision = screeningDecisions.decide(normalizedPassword);
        if (decision == PasswordScreeningDecisionService.Decision.REJECT_COMPROMISED) {
            throw new IllegalArgumentException("Password was found in a known breach");
        }
        return Objects.requireNonNull(decision, "Password screening decision is required");
    }

    /**
     * Records a failed authentication attempt.
     *<p>
     * Retries optimistic-lock conflicts using a fresh transaction
     * for each attempt. Unrelated failures are not retried.
     *
     * @param userId user whose failed attempt is being recorded
     */
    public void recordFailedAuthenticationAttempt(UUID userId) {

        Objects.requireNonNull(userId, USER_ID_REQUIRED);

        for (int attempt = 1; ; attempt++) {

            try {
                failureWriter.recordFailedAttempt(userId);
                return;

            } catch (OptimisticLockingFailureException
                     | OptimisticLockException exception) {

                if (attempt >= MAX_FAILED_ATTEMPT_WRITES) {
                    throw exception;
                }
            }
        }
    }

}
