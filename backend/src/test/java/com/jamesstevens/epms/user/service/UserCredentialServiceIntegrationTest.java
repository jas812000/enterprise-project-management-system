package com.jamesstevens.epms.user.service;

import com.jamesstevens.epms.organization.dto.CreateOrganizationRequest;
import com.jamesstevens.epms.organization.service.OrganizationService;
import com.jamesstevens.epms.user.dto.CreateUserRequest;
import com.jamesstevens.epms.user.entity.UserCredential;
import com.jamesstevens.epms.user.repository.UserCredentialRepository;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.security.SecureRandom;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.jamesstevens.epms.security.service.PasswordScreeningDecisionService;
import com.jamesstevens.epms.security.service.ProvisionalCredentialIssuer;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class UserCredentialServiceIntegrationTest {

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
    private UserCredentialService credentialService;

    @MockitoBean
    private PasswordScreeningDecisionService screeningDecisions;

    @MockitoBean
    private ProvisionalCredentialIssuer provisionalIssuer;

    @BeforeEach
    void screeningIsDeterministic() {
        when(screeningDecisions.decide(anyString()))
                .thenReturn(PasswordScreeningDecisionService.Decision.VERIFIED);
    }

    @Autowired
    private UserCredentialRepository credentialRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private record TestIds(UUID organizationId, UUID userId) {
    }

    /**
     * Creates committed prerequisites visible to REQUIRES_NEW.
     */
    private TestIds createTestCredential(String employeeNumber) {

        TransactionTemplate transaction =
                new TransactionTemplate(transactionManager);

        return transaction.execute(_ -> {

            // This test container may retain users referenced by immutable audit events.
            // EPMS is single-organization: reuse its existing fixture organization.
            UUID organizationId = jdbcTemplate.query(
                    "SELECT organization_id FROM organizations ORDER BY organization_id LIMIT 1",
                    rs -> rs.next() ? rs.getObject(1, UUID.class) : null);
            if (organizationId == null) {
                organizationId = organizationService.createOrganization(
                        new CreateOrganizationRequest(
                                "EPMS Test Organization",
                                "Credential transaction integration test"
                        )).organizationId();
            }

            var user = userService.createUser(
                    new CreateUserRequest(
                            null,
                            null,
                            HUMAN_ACCOUNT_TYPE_ID,
                            employeeNumber,
                            "Credential",
                            "Tester",
                            employeeNumber.toLowerCase()
                                    + "@example.com"
                    )
            );

            credentialRepository.saveAndFlush(
                    new UserCredential(
                            user.userId(),
                            "encoded-test-hash",
                            "ARGON2ID",
                            false
                    )
            );

            return new TestIds(
                    organizationId,
                    user.userId()
            );
        });
    }

    /**
     * Removes only records created by this test.
     */
    private void cleanup(TestIds ids) {

        TransactionTemplate transaction =
                new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(_ -> {
            // Audit history is immutable and references the user. Never delete an
            // audited identity or bypass the audit foreign keys for test cleanup.
            Integer auditReferences = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM authentication_security_events " +
                    "WHERE target_user_id = ? OR actor_user_id = ?",
                    Integer.class, ids.userId(), ids.userId());
            if (auditReferences != null && auditReferences > 0) {
                return;
            }

            entityManager.createNativeQuery(
                            "DELETE FROM user_credentials " +
                                    "WHERE user_id = :userId"
                    )
                    .setParameter("userId", ids.userId())
                    .executeUpdate();

            entityManager.createNativeQuery(
                            "DELETE FROM users " +
                                    "WHERE user_id = :userId"
                    )
                    .setParameter("userId", ids.userId())
                    .executeUpdate();

            // Retained audited users must retain the one fixture organization.
            Integer remainingUsers = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM users", Integer.class);
            if (remainingUsers != null && remainingUsers == 0) {
                entityManager.createNativeQuery(
                                "DELETE FROM organizations " +
                                        "WHERE organization_id = :organizationId"
                        )
                        .setParameter("organizationId", ids.organizationId())
                        .executeUpdate();
            }
        });
    }

    /** B2D-4C: audit and password change are visible together inside the same transaction. */
    @Test
    void successfulPasswordChangeWritesMandatoryAuditInSameTransaction() {
        TestIds ids = createTestCredential("B2D4C-" + UUID.randomUUID().toString().substring(0, 8));
        String oldPassword = "ValidOldPassword!2026-A";
        String newPassword = "ValidNewPassword!2026-B";
        jdbcTemplate.update("UPDATE user_credentials SET password_hash=? WHERE user_id=?",
                passwordEncoder.encode(oldPassword), ids.userId());
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                credentialService.changePassword(ids.userId(), oldPassword, newPassword);
                assertEquals(1, jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM authentication_security_events " +
                        "WHERE target_user_id=? AND event_type='PASSWORD_CHANGED'",
                        Integer.class, ids.userId()));
                assertTrue(credentialService.verifyPassword(ids.userId(), newPassword));
                status.setRollbackOnly(); // no audit FK residue in the committed fixture
            });
            assertTrue(credentialService.verifyPassword(ids.userId(), oldPassword));
            assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM authentication_security_events " +
                    "WHERE target_user_id=? AND event_type='PASSWORD_CHANGED'",
                    Integer.class, ids.userId()));
        } finally {
            cleanup(ids);
        }
    }

    /** B2D-4C: PostgreSQL rejects mandatory audit and the credential change rolls back. */
    @Test
    void mandatoryAuditInsertFailureRollsBackPasswordChange() {
        TestIds ids = createTestCredential("B2D4CF-" + UUID.randomUUID().toString().substring(0, 8));
        String oldPassword = "ValidOldPassword!2026-A";
        String newPassword = "ValidNewPassword!2026-B";
        jdbcTemplate.update("UPDATE user_credentials SET password_hash=? WHERE user_id=?",
                passwordEncoder.encode(oldPassword), ids.userId());
        String before = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM user_credentials WHERE user_id=?", String.class, ids.userId());
        String function = "epms_test_b2d4c_reject_password_audit";
        String trigger = "epms_test_b2d4c_password_audit_failure";
        try {
            jdbcTemplate.execute("CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql AS $$ " +
                    "BEGIN IF NEW.event_type = 'PASSWORD_CHANGED' THEN " +
                    "RAISE EXCEPTION 'test-only mandatory password audit failure'; END IF; " +
                    "RETURN NEW; END $$");
            jdbcTemplate.execute("CREATE TRIGGER " + trigger +
                    " BEFORE INSERT ON authentication_security_events " +
                    "FOR EACH ROW EXECUTE FUNCTION " + function + "()");
            assertThrows(org.springframework.dao.DataAccessException.class,
                    () -> credentialService.changePassword(ids.userId(), oldPassword, newPassword));
            assertEquals(before, jdbcTemplate.queryForObject(
                    "SELECT password_hash FROM user_credentials WHERE user_id=?", String.class, ids.userId()));
            assertTrue(credentialService.verifyPassword(ids.userId(), oldPassword));
            assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM authentication_security_events " +
                    "WHERE target_user_id=? AND event_type='PASSWORD_CHANGED'",
                    Integer.class, ids.userId()));
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + trigger + " ON authentication_security_events");
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + function + "()");
            cleanup(ids);
        }
    }

    /**
     * Confirms that a REQUIRES_NEW update survives rollback
     * of the surrounding transaction.
     */
    @Test
    void failedAttemptPersistsWhenOuterTransactionRollsBack() {

        TestIds ids = createTestCredential("EMP-CRED-TX-001");

        try {
            TransactionTemplate outerTransaction =
                    new TransactionTemplate(transactionManager);

            assertThrows(
                    IllegalStateException.class,
                    () -> outerTransaction.execute(_ -> {

                        credentialService
                                .recordFailedAuthenticationAttempt(
                                        ids.userId()
                                );

                        throw new IllegalStateException(
                                "Simulated authentication rollback"
                        );
                    })
            );

            TransactionTemplate verification =
                    new TransactionTemplate(transactionManager);

            UserCredential persisted = verification.execute(
                    _ -> credentialRepository
                            .findByUserId(ids.userId())
                            .orElseThrow()
            );

            assertNotNull(persisted);
            assertEquals(1, persisted.getFailedAttemptCount());
            assertNotNull(persisted.getLastFailedAttemptAt());
            assertEquals(1L, persisted.getVersion());

        } finally {
            cleanup(ids);
        }
    }

    /**
     * Confirms that separate service invocations persist
     * consecutive failed attempts.
     */
    @Test
    void consecutiveFailedAttemptsArePersisted() {

        TestIds ids = createTestCredential("EMP-CRED-TX-002");

        try {
            credentialService.recordFailedAuthenticationAttempt(
                    ids.userId()
            );

            credentialService.recordFailedAuthenticationAttempt(
                    ids.userId()
            );

            TransactionTemplate verification =
                    new TransactionTemplate(transactionManager);

            UserCredential persisted = verification.execute(
                    _ -> credentialRepository
                            .findByUserId(ids.userId())
                            .orElseThrow()
            );

            assertNotNull(persisted);
            assertEquals(2, persisted.getFailedAttemptCount());
            assertNotNull(persisted.getLastFailedAttemptAt());
            assertEquals(2L, persisted.getVersion());

        } finally {
            cleanup(ids);
        }
    }




    /** B2D-4B Stage 2: a real Java password-change transaction serializes SQL issuance. */
    @Test
    void javaPasswordChangeSerializesWithTokenIssuance() throws Exception {
        TestIds ids = createTestCredential("B2D4B-" + UUID.randomUUID().toString().substring(0, 8));
        String oldPassword = "ValidOldPassword!2026-A";
        String newPassword = "ValidNewPassword!2026-B";
        jdbcTemplate.update("UPDATE user_credentials SET password_hash=? WHERE user_id=?",
                passwordEncoder.encode(oldPassword), ids.userId());
        var changed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        byte[] access = new byte[32];
        byte[] renewal = new byte[32];
        new SecureRandom().nextBytes(access);
        new SecureRandom().nextBytes(renewal);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var passwordWriter = pool.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    credentialService.changePassword(ids.userId(), oldPassword, newPassword);
                    changed.countDown();
                    try {
                        if (!release.await(20, TimeUnit.SECONDS))
                            throw new IllegalStateException("Password writer release timed out");
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(ex);
                    }
                });
            });
            try {
                assertTrue(changed.await(20, TimeUnit.SECONDS), "Password service did not reach commit barrier");
                var issuance = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                    UUID session = jdbcTemplate.queryForObject(
                            "SELECT public.epms_auth_issue(?,?,?)", UUID.class,
                            ids.userId(), access, renewal);
                    Integer consistent = jdbcTemplate.queryForObject(
                            "SELECT count(*) FROM user_auth_sessions s JOIN user_credentials c " +
                            "ON c.user_id=s.user_id WHERE s.session_id=? AND s.credential_version=c.password_changed_at",
                            Integer.class, session);
                    status.setRollbackOnly();
                    return consistent;
                }));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                boolean observedWait = false;
                while (System.nanoTime() < deadline) {
                    Integer waiting = jdbcTemplate.queryForObject(
                            "SELECT count(*) FROM pg_catalog.pg_stat_activity " +
                            "WHERE datname=current_database() AND wait_event_type='Lock' AND wait_event='advisory'",
                            Integer.class);
                    if (waiting != null && waiting > 0) { observedWait = true; break; }
                    Thread.sleep(25);
                }
                assertTrue(observedWait, "Issuance must wait for the Java password writer advisory lock");
                release.countDown();
                passwordWriter.get(20, TimeUnit.SECONDS);
                assertEquals(1, issuance.get(20, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
        } finally {
            cleanup(ids);
        }
    }


    /** B2D-4B Stage 2 remaining: real failure-writer and independent-user issuance. */
    @Test
    void javaFailedAttemptWriterSerializesWithTokenIssuance() throws Exception {
        TestIds ids = createTestCredential("B2D4BF-" + UUID.randomUUID().toString().substring(0, 8));
        byte[] access = new byte[32];
        byte[] renewal = new byte[32];
        new SecureRandom().nextBytes(access);
        new SecureRandom().nextBytes(renewal);
        // A separate JDBC transaction temporarily holds the row, causing the real
        // Java failure writer to pause AFTER acquiring the advisory lock.
        try (var blocker = jdbcTemplate.getDataSource().getConnection();
             var pool = Executors.newFixedThreadPool(2)) {
            blocker.setAutoCommit(false);
            try {
                try (var lock = blocker.prepareStatement(
                        "SELECT user_id FROM user_credentials WHERE user_id=? FOR UPDATE")) {
                    lock.setObject(1, ids.userId());
                    try (var rs = lock.executeQuery()) {
                        assertTrue(rs.next());
                    }
                }
                var writer = pool.submit(() -> credentialService.recordFailedAuthenticationAttempt(ids.userId()));
                awaitCredentialRowWait(10);
                var issuance = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                    UUID session = jdbcTemplate.queryForObject(
                            "SELECT public.epms_auth_issue(?,?,?)", UUID.class,
                            ids.userId(), access, renewal);
                    Integer attempts = jdbcTemplate.queryForObject(
                            "SELECT failed_attempt_count FROM user_credentials WHERE user_id=?",
                            Integer.class, ids.userId());
                    status.setRollbackOnly();
                    return attempts;
                }));
                awaitAdvisoryWaitForStage2(10);
                blocker.commit();
                writer.get(20, TimeUnit.SECONDS);
                assertEquals(1, issuance.get(20, TimeUnit.SECONDS),
                        "Issuance must observe the committed Java failure writer state");
            } finally {
                blocker.rollback();
            }
        } finally {
            cleanup(ids);
        }
    }

    /** A held Java password writer for A must not block issuance for B. */
    @Test
    void javaPasswordWriterForDifferentUserDoesNotBlockIssuance() throws Exception {
        TestIds a = createTestCredential("B2D4BA-" + UUID.randomUUID().toString().substring(0, 8));
        UUID b = null;
        var changed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            b = new TransactionTemplate(transactionManager).execute(status -> {
                String employee = "B2D4BB-" + UUID.randomUUID().toString().substring(0, 8);
                var user = userService.createUser(new CreateUserRequest(
                        null, null, HUMAN_ACCOUNT_TYPE_ID, employee,
                        "Credential", "Independent", employee.toLowerCase() + "@example.com"));
                credentialRepository.saveAndFlush(new UserCredential(
                        user.userId(), "encoded-test-hash", "ARGON2ID", false));
                return user.userId();
            });
            final UUID userB = b;
            String oldPassword = "ValidOldPassword!2026-A";
            jdbcTemplate.update("UPDATE user_credentials SET password_hash=? WHERE user_id=?",
                    passwordEncoder.encode(oldPassword), a.userId());
            byte[] access = new byte[32];
            byte[] renewal = new byte[32];
            new SecureRandom().nextBytes(access);
            new SecureRandom().nextBytes(renewal);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var writer = pool.submit(() -> new TransactionTemplate(transactionManager)
                        .executeWithoutResult(status -> {
                            credentialService.changePassword(a.userId(), oldPassword,
                                    "ValidNewPassword!2026-B");
                            changed.countDown();
                            try {
                                if (!release.await(20, TimeUnit.SECONDS))
                                    throw new IllegalStateException("Writer release timed out");
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(e);
                            }
                        }));
                try {
                    assertTrue(changed.await(20, TimeUnit.SECONDS));
                    var issuance = pool.submit(() -> new TransactionTemplate(transactionManager)
                            .execute(status -> {
                                UUID session = jdbcTemplate.queryForObject(
                                        "SELECT public.epms_auth_issue(?,?,?)", UUID.class,
                                        userB, access, renewal);
                                status.setRollbackOnly();
                                return session;
                            }));
                    assertNotNull(issuance.get(10, TimeUnit.SECONDS),
                            "Unrelated user's issuance must complete while A is locked");
                    assertEquals(1L, release.getCount(),
                            "User A must still be held when User B completes");
                } finally {
                    release.countDown();
                    writer.get(20, TimeUnit.SECONDS);
                }
            }
        } finally {
            release.countDown();
            if (b != null) {
                UUID userB = b;
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    entityManager.createNativeQuery("DELETE FROM user_credentials WHERE user_id=:id")
                            .setParameter("id", userB).executeUpdate();
                    entityManager.createNativeQuery("DELETE FROM users WHERE user_id=:id")
                            .setParameter("id", userB).executeUpdate();
                });
            }
            cleanup(a);
        }
    }

    private void awaitCredentialRowWait(int seconds) throws Exception {
        awaitPostgresWait("row", seconds);
    }

    private void awaitAdvisoryWaitForStage2(int seconds) throws Exception {
        awaitPostgresWait("advisory", seconds);
    }

    private void awaitPostgresWait(String kind, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_catalog.pg_stat_activity " +
                    "WHERE datname=current_database() AND wait_event_type='Lock' " +
                    "AND ((? = 'row' AND wait_event IN ('tuple','transactionid')) " +
                    "OR (? <> 'row' AND wait_event=?))", Integer.class, kind, kind, kind);
            if (count != null && count > 0) return;
            Thread.sleep(25);
        }
        fail("Expected PostgreSQL " + kind + " lock wait");
    }


    /** B2D-4B Stage 3: rolled-back Java password changes cannot affect issuance eligibility. */
    @Test
    void rolledBackJavaPasswordChangeReleasesIssuanceAndPreservesCredential() throws Exception {
        TestIds ids = createTestCredential("B2D4BR-" + UUID.randomUUID().toString().substring(0, 8));
        String original = "ValidOldPassword!2026-A";
        jdbcTemplate.update("UPDATE user_credentials SET password_hash=? WHERE user_id=?",
                passwordEncoder.encode(original), ids.userId());
        String before = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM user_credentials WHERE user_id=?", String.class, ids.userId());
        var changed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        byte[] access = new byte[32];
        byte[] renewal = new byte[32];
        new SecureRandom().nextBytes(access);
        new SecureRandom().nextBytes(renewal);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var writer = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                credentialService.changePassword(ids.userId(), original, "ValidNewPassword!2026-B");
                changed.countDown();
                try {
                    if (!release.await(20, TimeUnit.SECONDS))
                        throw new IllegalStateException("Password rollback release timed out");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
                status.setRollbackOnly();
            }));
            try {
                assertTrue(changed.await(20, TimeUnit.SECONDS));
                var issuance = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                    UUID session = jdbcTemplate.queryForObject("SELECT public.epms_auth_issue(?,?,?)",
                            UUID.class, ids.userId(), access, renewal);
                    status.setRollbackOnly();
                    return session;
                }));
                awaitAdvisoryWaitForStage2(10);
                release.countDown();
                writer.get(20, TimeUnit.SECONDS);
                assertNotNull(issuance.get(20, TimeUnit.SECONDS));
                assertEquals(before, jdbcTemplate.queryForObject(
                        "SELECT password_hash FROM user_credentials WHERE user_id=?", String.class, ids.userId()));
                assertEquals(0, jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM user_access_tokens WHERE token_hash=?", Integer.class, access));
            } finally {
                release.countDown();
                writer.get(20, TimeUnit.SECONDS);
            }
        } finally {
            cleanup(ids);
        }
    }

}
