package com.jamesstevens.epms.user.service;

import com.jamesstevens.epms.common.exception.ResourceNotFoundException;
import com.jamesstevens.epms.user.entity.UserCredential;
import com.jamesstevens.epms.user.repository.UserCredentialRepository;

import org.springframework.stereotype.Service;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Persists a failed authentication attempt in an independent transaction.
 *
 * Each invocation loads the latest credential state.
 */
@Service
public class UserCredentialFailureWriter {

    private final UserCredentialRepository credentialRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public UserCredentialFailureWriter(
            UserCredentialRepository credentialRepository) {

        this.credentialRepository =
                Objects.requireNonNull(credentialRepository);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailedAttempt(UUID userId) {

        Objects.requireNonNull(userId, "User ID is required");

        // Match V20 token issuance before acquiring the credential row lock.
        // The REQUIRES_NEW transaction retains this lock through commit/rollback.
        entityManager.createNativeQuery(
                "SELECT pg_catalog.pg_advisory_xact_lock(" +
                "pg_catalog.hashtextextended(CAST(:userId AS text), 82302026))")
                .setParameter("userId", userId.toString())
                .getSingleResult();

        UserCredential credential = credentialRepository
                .findByUserIdForUpdate(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User credentials not found"
                ));

        credential.recordFailedAttempt();

        credentialRepository.saveAndFlush(credential);
    }
}
