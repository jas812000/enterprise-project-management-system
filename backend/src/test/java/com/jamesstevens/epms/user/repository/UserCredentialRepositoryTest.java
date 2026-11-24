package com.jamesstevens.epms.user.repository;

import com.jamesstevens.epms.organization.dto.CreateOrganizationRequest;
import com.jamesstevens.epms.organization.service.OrganizationService;
import com.jamesstevens.epms.user.dto.CreateUserRequest;
import com.jamesstevens.epms.user.entity.UserCredential;
import com.jamesstevens.epms.user.service.UserService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import jakarta.persistence.EntityManager;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class UserCredentialRepositoryTest {

    private static final UUID HUMAN_ACCOUNT_TYPE_ID =
            UUID.fromString(
                    "0199a000-0000-7000-8000-000000000001"
            );

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:18")
                    .withInitScript("test-db-init.sql");

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private UserService userService;

    @Autowired
    private UserCredentialRepository credentialRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * Verifies that credentials are persisted and can be
     * retrieved using the owning user's identifier.
     */
    @Test
    @Transactional
    void savesAndFindsCredentialByUserId() {

        organizationService.createOrganization(
                new CreateOrganizationRequest(
                        "EPMS Test Organization",
                        "Credential persistence integration test"
                )
        );

        var user = userService.createUser(
                new CreateUserRequest(
                        null,
                        null,
                        HUMAN_ACCOUNT_TYPE_ID,
                        "EMP-CRED-001",
                        "Jane",
                        "Doe",
                        "jane.credentials@example.com"
                )
        );

        UserCredential credential = new UserCredential(
                user.userId(),
                "test-encoded-password-hash",
                "TEST",
                true
        );

        credentialRepository.saveAndFlush(credential);

        entityManager.clear();

        UserCredential persisted = credentialRepository
                .findByUserId(user.userId())
                .orElseThrow();

        assertNotNull(persisted.getUserCredentialId());
        assertEquals(7, persisted.getUserCredentialId().version());
        assertEquals(user.userId(), persisted.getUserId());

        assertEquals(
                "test-encoded-password-hash",
                persisted.getPasswordHash()
        );

        assertEquals(
                "TEST",
                persisted.getPasswordAlgorithmCode()
        );

        assertTrue(persisted.isMustChangePassword());
        assertEquals(0, persisted.getFailedAttemptCount());

        assertNotNull(persisted.getPasswordChangedAt());
        assertNotNull(persisted.getCreatedAt());
        assertNotNull(persisted.getUpdatedAt());
        assertNotNull(persisted.getVersion());
    }

    /**
     * Verifies that PostgreSQL prevents multiple credential
     * records from being associated with the same user.
     */
    @Test
    @Transactional
    void rejectsDuplicateCredentialsForSameUser() {

        organizationService.createOrganization(
                new CreateOrganizationRequest(
                        "EPMS Test Organization",
                        "Credential uniqueness integration test"
                )
        );

        var user = userService.createUser(
                new CreateUserRequest(
                        null,
                        null,
                        HUMAN_ACCOUNT_TYPE_ID,
                        "EMP-CRED-002",
                        "John",
                        "Doe",
                        "john.credentials@example.com"
                )
        );

        UserCredential firstCredential = new UserCredential(
                user.userId(),
                "first-test-hash",
                "TEST",
                false
        );

        credentialRepository.saveAndFlush(firstCredential);

        UserCredential duplicateCredential = new UserCredential(
                user.userId(),
                "second-test-hash",
                "TEST",
                false
        );

        assertThrows(
                DataIntegrityViolationException.class,
                () -> credentialRepository.saveAndFlush(
                        duplicateCredential
                )
        );
    }

    /**
     * Verifies that failed authentication attempts update
     * the credential counter and failure timestamp.
     */
    @Test
    @Transactional
    void persistsFailedAuthenticationAttempt() {

        organizationService.createOrganization(
                new CreateOrganizationRequest(
                        "EPMS Test Organization",
                        "Failed authentication tracking test"
                )
        );

        var user = userService.createUser(
                new CreateUserRequest(
                        null,
                        null,
                        HUMAN_ACCOUNT_TYPE_ID,
                        "EMP-CRED-003",
                        "Alice",
                        "Smith",
                        "alice.credentials@example.com"
                )
        );

        UserCredential credential = new UserCredential(
                user.userId(),
                "test-encoded-password-hash",
                "TEST",
                false
        );

        credentialRepository.saveAndFlush(credential);

        credential.recordFailedAttempt();
        credential.recordFailedAttempt();

        credentialRepository.saveAndFlush(credential);

        entityManager.clear();

        UserCredential persisted = credentialRepository
                .findByUserId(user.userId())
                .orElseThrow();

        assertEquals(2, persisted.getFailedAttemptCount());
        assertNotNull(persisted.getLastFailedAttemptAt());

        assertEquals(1L, persisted.getVersion());
    }

    /**
     * Verifies that changing a password updates the credential
     * and resets authentication failure tracking.
     */
    @Test
    @Transactional
    void persistsPasswordChangeAndResetsFailureTracking() {

        organizationService.createOrganization(
                new CreateOrganizationRequest(
                        "EPMS Test Organization",
                        "Password change persistence test"
                )
        );

        var user = userService.createUser(
                new CreateUserRequest(
                        null,
                        null,
                        HUMAN_ACCOUNT_TYPE_ID,
                        "EMP-CRED-004",
                        "Robert",
                        "Smith",
                        "robert.credentials@example.com"
                )
        );

        UserCredential credential = new UserCredential(
                user.userId(),
                "original-test-hash",
                "TEST",
                true
        );

        credentialRepository.saveAndFlush(credential);

        credential.recordFailedAttempt();
        credential.recordFailedAttempt();

        credentialRepository.saveAndFlush(credential);

        var originalPasswordChangedAt =
                credential.getPasswordChangedAt();

        credential.changePassword(
                "updated-test-hash",
                "TEST-V2"
        );

        credentialRepository.saveAndFlush(credential);

        entityManager.clear();

        UserCredential persisted = credentialRepository
                .findByUserId(user.userId())
                .orElseThrow();

        assertEquals(
                "updated-test-hash",
                persisted.getPasswordHash()
        );

        assertEquals(
                "TEST-V2",
                persisted.getPasswordAlgorithmCode()
        );

        assertFalse(persisted.isMustChangePassword());
        assertEquals(0, persisted.getFailedAttemptCount());

        assertNotNull(persisted.getPasswordChangedAt());

        assertFalse(
                persisted.getPasswordChangedAt()
                        .isBefore(originalPasswordChangedAt)
        );

        assertEquals(2L, persisted.getVersion());
    }


    /**
     * Verifies that a stale transaction cannot overwrite
     * a newer password credential update.
     */
    @Test
    void rejectsStaleCredentialUpdate() {

        TransactionTemplate transactionTemplate =
                new TransactionTemplate(transactionManager);

        // Create and commit valid prerequisite records.
        UUID[] createdIds = transactionTemplate.execute(_ -> {

            var organization = organizationService.createOrganization(
                    new CreateOrganizationRequest(
                            "EPMS Test Organization",
                            "Credential optimistic-locking test"
                    )
            );

            var user = userService.createUser(
                    new CreateUserRequest(
                            null,
                            null,
                            HUMAN_ACCOUNT_TYPE_ID,
                            "EMP-CRED-005",
                            "Michael",
                            "Smith",
                            "michael.credentials@example.com"
                    )
            );

            credentialRepository.saveAndFlush(
                    new UserCredential(
                            user.userId(),
                            "original-test-hash",
                            "TEST",
                            false
                    )
            );

            return new UUID[] {
                    organization.organizationId(),
                    user.userId()
            };
        });

        assertNotNull(createdIds);

        UUID organizationId = createdIds[0];
        UUID userId = createdIds[1];

        EntityManager managerA =
                entityManagerFactory.createEntityManager();

        EntityManager managerB =
                entityManagerFactory.createEntityManager();

        try {
            managerA.getTransaction().begin();
            managerB.getTransaction().begin();

            UserCredential credentialA = managerA
                    .createQuery(
                            "SELECT c FROM UserCredential c " +
                                    "WHERE c.userId = :userId",
                            UserCredential.class
                    )
                    .setParameter("userId", userId)
                    .getSingleResult();

            UserCredential credentialB = managerB
                    .createQuery(
                            "SELECT c FROM UserCredential c " +
                                    "WHERE c.userId = :userId",
                            UserCredential.class
                    )
                    .setParameter("userId", userId)
                    .getSingleResult();

            assertEquals(
                    credentialA.getVersion(),
                    credentialB.getVersion()
            );

            // Transaction A changes the password and commits.
            credentialA.changePassword(
                    "password-hash-from-A",
                    "TEST"
            );

            managerA.getTransaction().commit();

            // Transaction B still holds the original version.
            credentialB.changePassword(
                    "password-hash-from-B",
                    "TEST"
            );

            assertThrows(
                    OptimisticLockException.class,
                    managerB::flush
            );

            // Verify that transaction A's password remains stored.
            UserCredential persisted = transactionTemplate.execute(
                    _ -> credentialRepository
                            .findByUserId(userId)
                            .orElseThrow()
            );

            assertNotNull(persisted);

            assertEquals(
                    "password-hash-from-A",
                    persisted.getPasswordHash()
            );

            assertEquals(1L, persisted.getVersion());

        } finally {
            if (managerA.getTransaction().isActive()) {
                managerA.getTransaction().rollback();
            }

            if (managerB.getTransaction().isActive()) {
                managerB.getTransaction().rollback();
            }

            managerA.close();
            managerB.close();

            // Remove committed test records in dependency order.
            transactionTemplate.executeWithoutResult(_ -> {
                entityManager.createNativeQuery(
                                "DELETE FROM user_credentials " +
                                        "WHERE user_id = :userId"
                        )
                        .setParameter("userId", userId)
                        .executeUpdate();

                entityManager.createNativeQuery(
                                "DELETE FROM users " +
                                        "WHERE user_id = :userId"
                        )
                        .setParameter("userId", userId)
                        .executeUpdate();

                entityManager.createNativeQuery(
                                "DELETE FROM organizations " +
                                        "WHERE organization_id = :organizationId"
                        )
                        .setParameter("organizationId", organizationId)
                        .executeUpdate();
            });
        }
    }

}
