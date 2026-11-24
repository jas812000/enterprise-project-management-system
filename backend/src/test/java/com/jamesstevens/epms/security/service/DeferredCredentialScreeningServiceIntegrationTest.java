package com.jamesstevens.epms.security.service;

import static org.junit.jupiter.api.Assertions.*;

import com.jamesstevens.epms.organization.dto.CreateOrganizationRequest;
import com.jamesstevens.epms.organization.service.OrganizationService;
import com.jamesstevens.epms.security.outage.PasswordScreeningOutageRecorder;
import com.jamesstevens.epms.user.dto.CreateUserRequest;
import com.jamesstevens.epms.user.entity.UserCredential;
import com.jamesstevens.epms.user.repository.UserCredentialRepository;
import com.jamesstevens.epms.user.service.UserService;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real PostgreSQL recovery persistence, transaction and stale-write tests. */
@SpringBootTest(properties = "epms.security.deferred-recovery.enabled=false")
@Testcontainers
@Import(DeferredCredentialScreeningServiceIntegrationTest.RecoveryTestConfiguration.class)
class DeferredCredentialScreeningServiceIntegrationTest {
    private static final String PASSWORD = "Correct horse battery staple! 2026";
    private static final UUID HUMAN_ACCOUNT_TYPE_ID =
            UUID.fromString("0199a000-0000-7000-8000-000000000001");
    private static final DeferredScreeningCipher TEST_CIPHER = new DeferredScreeningCipher(new byte[32]);
    private static final AtomicReference<PwnedPasswordScreeningService.RangeTransport> TRANSPORT =
            new AtomicReference<>(prefix -> "");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18")
            .withInitScript("test-db-init.sql");

    @TestConfiguration(proxyBeanMethods = false)
    static class RecoveryTestConfiguration {
        @Bean
        @Primary
        ProvisionalCredentialIssuer recoveryTestIssuer(UserCredentialRepository credentials,
                PasswordScreeningOutageRecorder outages) {
            return new ProvisionalCredentialIssuer(credentials, outages,
                    TEST_CIPHER, "test-key-v1", Clock.systemUTC());
        }

        @Bean
        @Primary
        DeferredCredentialScreeningService recoveryTestService(UserCredentialRepository credentials,
                JdbcTemplate jdbc, PlatformTransactionManager manager) {
            return new DeferredCredentialScreeningService(credentials,
                    new PwnedPasswordScreeningService(prefix -> TRANSPORT.get().fetch(prefix)),
                    jdbc, TEST_CIPHER, "test-key-v1", Clock.systemUTC(), new TransactionTemplate(manager));
        }
    }

    @Autowired private DeferredCredentialScreeningService recovery;
    @Autowired private ProvisionalCredentialIssuer issuer;
    @Autowired private UserCredentialRepository credentials;
    @Autowired private OrganizationService organizations;
    @Autowired private UserService users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private record Ids(UUID organizationId, UUID userId) { }

    @BeforeEach
    void resetTransport() { TRANSPORT.set(prefix -> ""); }

    private Ids createUser() {
        String employee = "B16-" + UUID.randomUUID().toString().substring(0, 12);
        return new TransactionTemplate(transactionManager).execute(status -> {
            var organization = organizations.createOrganization(
                    new CreateOrganizationRequest("B16 Test Organization", "Recovery integration test"));
            var user = users.createUser(new CreateUserRequest(null, null,
                    HUMAN_ACCOUNT_TYPE_ID, employee, "Recovery", "Tester",
                    employee.toLowerCase() + "@example.com"));
            return new Ids(organization.organizationId(), user.userId());
        });
    }

    private UserCredential provisional(Ids ids) {
        issuer.issueAfterConfirmedOutage(
                new UserCredential(ids.userId(), "encoded-argon2id-hash", "ARGON2ID", false),
                PASSWORD, "PROVIDER_TIMEOUT");
        return credentials.findByUserId(ids.userId()).orElseThrow();
    }

    private int events(UUID userId, String type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM password_security_events "
                + "WHERE user_id = ? AND event_type = ?", Integer.class, userId, type);
    }

    private void cleanup(Ids ids) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            var outageIds = jdbc.queryForList(
                    "SELECT screening_outage_id FROM user_credentials WHERE user_id = ? "
                    + "AND screening_outage_id IS NOT NULL", UUID.class, ids.userId());
            jdbc.update("DELETE FROM password_security_events WHERE user_id = ?", ids.userId());
            jdbc.update("DELETE FROM user_credentials WHERE user_id = ?", ids.userId());
            for (UUID outageId : outageIds) {
                jdbc.update("DELETE FROM password_security_events WHERE outage_id = ?", outageId);
                jdbc.update("DELETE FROM password_screening_outages WHERE outage_id = ?", outageId);
            }
            jdbc.update("DELETE FROM users WHERE user_id = ?", ids.userId());
            jdbc.update("DELETE FROM organizations WHERE organization_id = ?", ids.organizationId());
        });
    }

    @Test
    void directProviderRunsWithoutSpringTransactionAndRejectsStalePasswordGeneration() throws Exception {
        Ids ids = createUser();
        try {
            UUID id = provisional(ids).getUserCredentialId();
            java.util.concurrent.atomic.AtomicBoolean providerObserved = new java.util.concurrent.atomic.AtomicBoolean();
            TRANSPORT.set(prefix -> {
                providerObserved.set(true);
                assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive(), "Provider must not run in a Spring transaction");
                try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                    pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                            jdbc.update("UPDATE user_credentials SET password_hash = ?, "
                                    + "password_changed_at = ?, version = version + 1 "
                                    + "WHERE user_credential_id = ?", "replacement-hash",
                                    OffsetDateTime.now().plusSeconds(2), id))).get(15,
                            java.util.concurrent.TimeUnit.SECONDS);
                }
                return "";
            });
            assertEquals(DeferredCredentialScreeningService.Outcome.NOT_PENDING, recovery.resolve(id));
            assertTrue(providerObserved.get());
            assertEquals("replacement-hash", credentials.findById(id).orElseThrow().getPasswordHash());
            assertEquals(0, events(ids.userId(), "PASSWORD_VERIFIED"));
            assertEquals(0, events(ids.userId(), "PASSWORD_COMPROMISED"));
        } finally { cleanup(ids); }
    }

    @Test
    void claimedProviderRunsWithoutTransactionAndCannotFinalizeAfterLeaseReplacement() {
        Ids ids = createUser();
        try {
            UUID id = provisional(ids).getUserCredentialId();
            var firstClaim = claimService.claim(1).getFirst();
            java.util.concurrent.atomic.AtomicBoolean providerObserved = new java.util.concurrent.atomic.AtomicBoolean();
            TRANSPORT.set(prefix -> {
                providerObserved.set(true);
                assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive(), "Claimed provider must run outside transaction");
                jdbc.update("UPDATE user_credentials SET screening_claim_expires_at = ? "
                        + "WHERE user_credential_id = ?", OffsetDateTime.now().minusSeconds(2), id);
                var replacement = claimService.claim(1);
                assertEquals(1, replacement.size());
                assertNotEquals(firstClaim.token(), replacement.getFirst().token());
                return "";
            });
            assertEquals(DeferredCredentialScreeningService.Outcome.NOT_PENDING,
                    claimedRecovery.resolveClaimed(firstClaim));
            assertTrue(providerObserved.get());
            assertFalse(claimService.release(firstClaim));
            assertEquals("PENDING_VERIFICATION", credentials.findById(id).orElseThrow().getScreeningStatus());
            assertEquals(0, events(ids.userId(), "PASSWORD_VERIFIED"));
        } finally { cleanup(ids); }
    }

    @Test
    void claimSkipsCredentialRowLockedByAnotherTransaction() throws Exception {
        Ids ids = createUser();
        try {
            UUID id = provisional(ids).getUserCredentialId();
            java.util.concurrent.CountDownLatch rowLocked = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch releaseRow = new java.util.concurrent.CountDownLatch(1);
            try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var holding = workers.submit(() -> new TransactionTemplate(transactionManager)
                        .executeWithoutResult(status -> {
                            jdbc.queryForList("SELECT user_credential_id FROM user_credentials "
                                    + "WHERE user_credential_id = ? FOR UPDATE", UUID.class, id);
                            rowLocked.countDown();
                            try {
                                if (!releaseRow.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                                    throw new AssertionError("Timed out holding credential row lock");
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(e);
                            }
                        }));
                try {
                    assertTrue(rowLocked.await(10, java.util.concurrent.TimeUnit.SECONDS));
                    var claiming = workers.submit(() -> claimService.claim(1));
                    assertTrue(claiming.get(8, java.util.concurrent.TimeUnit.SECONDS).isEmpty(),
                            "SKIP LOCKED must not wait on a credential row held by another transaction");
                } finally {
                    releaseRow.countDown();
                    holding.get(10, java.util.concurrent.TimeUnit.SECONDS);
                }
            }
            assertEquals(1, claimService.claim(1).size(),
                    "Credential remains claimable after the other transaction completes");
        } finally { cleanup(ids); }
    }

    @Test
    void releasingCurrentClaimDuringProviderCallPreventsFinalization() {
        Ids ids = createUser();
        try {
            UUID id = provisional(ids).getUserCredentialId();
            var claim = claimService.claim(1).getFirst();
            java.util.concurrent.atomic.AtomicBoolean providerObserved = new java.util.concurrent.atomic.AtomicBoolean();
            TRANSPORT.set(prefix -> {
                providerObserved.set(true);
                assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive(), "Provider must run outside database transaction");
                assertTrue(claimService.release(claim), "Current owner should be able to release claim");
                return "";
            });
            assertEquals(DeferredCredentialScreeningService.Outcome.NOT_PENDING,
                    claimedRecovery.resolveClaimed(claim));
            assertTrue(providerObserved.get());
            assertEquals("PENDING_VERIFICATION", credentials.findById(id).orElseThrow().getScreeningStatus());
            assertEquals(0, events(ids.userId(), "PASSWORD_VERIFIED"));
            assertEquals(0, events(ids.userId(), "PASSWORD_COMPROMISED"));
            assertEquals(1, claimService.claim(1).size(),
                    "Released credential must be available to a subsequent worker");
        } finally { cleanup(ids); }
    }

    @Test
    void cleanRecoveryPersistsVerifiedStatusAndSingleEvent() {
        Ids ids = createUser();
        try {
            UserCredential original = provisional(ids);
            UUID id = original.getUserCredentialId();
            UUID outageId = original.getScreeningOutageId();
            assertEquals(DeferredCredentialScreeningService.Outcome.VERIFIED, recovery.resolve(id));
            UserCredential stored = credentials.findById(id).orElseThrow();
            assertEquals("VERIFIED", stored.getScreeningStatus());
            assertNull(stored.getScreeningDigestCiphertext());
            assertNull(stored.getScreeningDigestKeyId());
            assertEquals(outageId, stored.getScreeningOutageId());
            assertEquals(1, events(ids.userId(), "PASSWORD_VERIFIED"));
            assertEquals(DeferredCredentialScreeningService.Outcome.NOT_PENDING, recovery.resolve(id));
            assertEquals(1, events(ids.userId(), "PASSWORD_VERIFIED"));
        } finally { cleanup(ids); }
    }

    @Test
    void compromisedRecoveryPersistsChangeRequiredAndEvent() {
        Ids ids = createUser();
        try {
            UserCredential original = provisional(ids);
            String digest = TEST_CIPHER.decrypt(original.getScreeningDigestCiphertext(),
                    original.getUserCredentialId().toString());
            TRANSPORT.set(prefix -> digest.substring(5) + ":5");
            assertEquals(DeferredCredentialScreeningService.Outcome.CHANGE_REQUIRED,
                    recovery.resolve(original.getUserCredentialId()));
            UserCredential stored = credentials.findById(original.getUserCredentialId()).orElseThrow();
            assertEquals("CHANGE_REQUIRED", stored.getScreeningStatus());
            assertTrue(stored.isMustChangePassword());
            assertNull(stored.getScreeningDigestCiphertext());
            assertEquals(1, events(ids.userId(), "PASSWORD_COMPROMISED"));
        } finally { cleanup(ids); }
    }

    @Test
    void providerFailureLeavesCredentialPendingWithoutEvent() {
        Ids ids = createUser();
        try {
            UserCredential original = provisional(ids);
            TRANSPORT.set(prefix -> { throw new IllegalStateException("test provider unavailable"); });
            assertEquals(DeferredCredentialScreeningService.Outcome.UNAVAILABLE,
                    recovery.resolve(original.getUserCredentialId()));
            UserCredential stored = credentials.findById(original.getUserCredentialId()).orElseThrow();
            assertEquals("PENDING_VERIFICATION", stored.getScreeningStatus());
            assertNotNull(stored.getScreeningDigestCiphertext());
            assertEquals(0, events(ids.userId(), "PASSWORD_VERIFIED"));
            assertEquals(0, events(ids.userId(), "PASSWORD_COMPROMISED"));
        } finally { cleanup(ids); }
    }

    @Test
    void callerTransactionIsRejectedBeforeProviderAccess() {
        Ids ids = createUser();
        try {
            UUID id = provisional(ids).getUserCredentialId();
            assertThrows(IllegalStateException.class, () ->
                    new TransactionTemplate(transactionManager).execute(status -> recovery.resolve(id)));
            assertEquals("PENDING_VERIFICATION", credentials.findById(id).orElseThrow().getScreeningStatus());
            assertEquals(0, events(ids.userId(), "PASSWORD_VERIFIED"));
        } finally { cleanup(ids); }
    }

    @Test
    void staleConcurrentPasswordUpdateCannotCommitRecoveryEvent() {
        Ids ids = createUser();
        try {
            UUID id = provisional(ids).getUserCredentialId();
            TRANSPORT.set(prefix -> {
                TransactionTemplate concurrent = new TransactionTemplate(transactionManager);
                concurrent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                concurrent.executeWithoutResult(status -> jdbc.update(
                        "UPDATE user_credentials SET password_hash = ?, "
                        + "password_changed_at = ?, screening_status = 'VERIFIED', "
                        + "screening_digest_ciphertext = NULL, screening_digest_key_id = NULL, "
                        + "provisional_issued_at = NULL, provisional_expires_at = NULL, "
                        + "screening_outage_id = NULL, version = version + 1 WHERE user_credential_id = ?",
                        "newer-argon2id-hash", OffsetDateTime.now().plusSeconds(1), id));
                return "";
            });
            assertEquals(DeferredCredentialScreeningService.Outcome.NOT_PENDING, recovery.resolve(id));
            UserCredential stored = credentials.findById(id).orElseThrow();
            assertEquals("newer-argon2id-hash", stored.getPasswordHash());
            assertEquals("VERIFIED", stored.getScreeningStatus());
            assertEquals(0, events(ids.userId(), "PASSWORD_VERIFIED"));
        } finally { cleanup(ids); }
    }

    @Autowired private DeferredRecoveryClaimService claimService;
    @Autowired private DeferredClaimedRecoveryService claimedRecovery;
    @Autowired private DeferredRecoveryBatchCoordinator coordinator;

    @Test
    void claimedRecoveryPersistsVerifiedEventAndPreventsDuplicateProcessing() {
        Ids ids = createUser();
        try {
            UUID id = provisional(ids).getUserCredentialId();
            var claimed = claimService.claim(1);
            assertEquals(1, claimed.size());
            assertEquals(id, claimed.getFirst().credentialId());
            assertTrue(claimService.claim(1).isEmpty());
            assertEquals(DeferredCredentialScreeningService.Outcome.VERIFIED,
                    claimedRecovery.resolveClaimed(claimed.getFirst()));
            assertEquals("VERIFIED", credentials.findById(id).orElseThrow().getScreeningStatus());
            assertEquals(1, events(ids.userId(), "PASSWORD_VERIFIED"));
            assertEquals(DeferredCredentialScreeningService.Outcome.NOT_PENDING,
                    claimedRecovery.resolveClaimed(claimed.getFirst()));
            assertEquals(1, events(ids.userId(), "PASSWORD_VERIFIED"));
        } finally { cleanup(ids); }
    }

    @Test
    void staleClaimCannotCommitAfterClaimTokenIsReplaced() {
        Ids ids = createUser();
        try {
            UUID id = provisional(ids).getUserCredentialId();
            var claim = claimService.claim(1).getFirst();
            UUID newer = UUID.randomUUID();
            jdbc.update("UPDATE user_credentials SET screening_claim_token = ? "
                    + "WHERE user_credential_id = ?", newer, id);
            assertEquals(DeferredCredentialScreeningService.Outcome.NOT_PENDING,
                    claimedRecovery.resolveClaimed(claim));
            assertFalse(claimService.release(claim));
            assertEquals("PENDING_VERIFICATION", credentials.findById(id).orElseThrow().getScreeningStatus());
            assertEquals(0, events(ids.userId(), "PASSWORD_VERIFIED"));
        } finally { cleanup(ids); }
    }

    @Test
    void providerFailurePersistsBackoffAndClearsClaim() {
        Ids ids = createUser();
        try {
            UUID id = provisional(ids).getUserCredentialId();
            TRANSPORT.set(prefix -> { throw new IllegalStateException("provider unavailable"); });
            var claim = claimService.claim(1).getFirst();
            assertEquals(DeferredCredentialScreeningService.Outcome.UNAVAILABLE,
                    claimedRecovery.resolveClaimed(claim));
            UserCredential stored = credentials.findById(id).orElseThrow();
            assertEquals("PENDING_VERIFICATION", stored.getScreeningStatus());
            assertEquals(1, stored.getScreeningRetryAttempts());
            assertNotNull(stored.getNextScreeningRetryAt());
            assertTrue(claimService.claim(1).isEmpty());
            assertEquals(0, events(ids.userId(), "PASSWORD_VERIFIED"));
        } finally { cleanup(ids); }
    }

    @Test
    void passwordChangeDuringProviderCallRejectsStaleResult() {
        Ids ids = createUser();
        try {
            UUID id = provisional(ids).getUserCredentialId();
            var claim = claimService.claim(1).getFirst();
            TRANSPORT.set(prefix -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> jdbc.update(
                        "UPDATE user_credentials SET password_hash = ?, "
                        + "password_changed_at = ?, screening_status = 'VERIFIED', "
                        + "screening_digest_ciphertext = NULL, screening_digest_key_id = NULL, "
                        + "version = version + 1 WHERE user_credential_id = ?",
                        "new-password-hash", OffsetDateTime.now().plusSeconds(1), id));
                return "";
            });
            assertEquals(DeferredCredentialScreeningService.Outcome.NOT_PENDING,
                    claimedRecovery.resolveClaimed(claim));
            assertEquals("new-password-hash", credentials.findById(id).orElseThrow().getPasswordHash());
            assertEquals(0, events(ids.userId(), "PASSWORD_VERIFIED"));
        } finally { cleanup(ids); }
    }

    @Test
    void twoConcurrentWorkersCannotProcessSameCredentialTwice() throws Exception {
        Ids ids = createUser();
        try {
            UUID id = provisional(ids).getUserCredentialId();
            java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
            TRANSPORT.set(prefix -> {
                calls.incrementAndGet();
                try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return "";
            });
            try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var ready = new java.util.concurrent.CountDownLatch(2);
                var start = new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.Callable<DeferredRecoveryBatchCoordinator.Result> task = () -> {
                    ready.countDown(); start.await(); return coordinator.process(1);
                };
                var first = pool.submit(task);
                var second = pool.submit(task);
                assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
                start.countDown();
                var a = first.get(30, java.util.concurrent.TimeUnit.SECONDS);
                var b = second.get(30, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(1, a.attempted() + b.attempted());
                assertEquals(1, a.verified() + b.verified());
                assertEquals(1, calls.get());
                assertEquals(1, events(ids.userId(), "PASSWORD_VERIFIED"));
                assertEquals("VERIFIED", credentials.findById(id).orElseThrow().getScreeningStatus());
            }
        } finally { cleanup(ids); }
    }


    // B18E PostgreSQL claim lease, stale-result and transaction regression coverage.
    @Test
    void expiredClaimCanBeReacquiredAndOldOwnerCannotReleaseIt() {
        Ids ids = createUser();
        try {
            UUID id = provisional(ids).getUserCredentialId();
            var oldClaim = claimService.claim(1).getFirst();
            jdbc.update("UPDATE user_credentials SET screening_claim_expires_at = ? "
                    + "WHERE user_credential_id = ?", OffsetDateTime.now().minusMinutes(1), id);
            var newClaim = claimService.claim(1).getFirst();
            assertEquals(id, newClaim.credentialId());
            assertNotEquals(oldClaim.token(), newClaim.token());
            assertFalse(claimService.release(oldClaim));
            assertEquals(newClaim.token(), jdbc.queryForObject(
                    "SELECT screening_claim_token FROM user_credentials WHERE user_credential_id = ?",
                    UUID.class, id));
            assertEquals(DeferredCredentialScreeningService.Outcome.NOT_PENDING,
                    claimedRecovery.resolveClaimed(oldClaim));
            assertEquals(0, events(ids.userId(), "PASSWORD_VERIFIED"));
            assertEquals(DeferredCredentialScreeningService.Outcome.VERIFIED,
                    claimedRecovery.resolveClaimed(newClaim));
            assertEquals(1, events(ids.userId(), "PASSWORD_VERIFIED"));
        } finally { cleanup(ids); }
    }

    @Test
    void expiredLeaseRejectsFinalizationEvenWithoutAnotherOwner() {
        Ids ids = createUser();
        try {
            UUID id = provisional(ids).getUserCredentialId();
            var claim = claimService.claim(1).getFirst();
            TRANSPORT.set(prefix -> {
                jdbc.update("UPDATE user_credentials SET screening_claim_expires_at = ? "
                        + "WHERE user_credential_id = ?", OffsetDateTime.now().minusSeconds(1), id);
                return "";
            });
            assertEquals(DeferredCredentialScreeningService.Outcome.NOT_PENDING,
                    claimedRecovery.resolveClaimed(claim));
            assertEquals("PENDING_VERIFICATION",
                    credentials.findById(id).orElseThrow().getScreeningStatus());
            assertEquals(0, events(ids.userId(), "PASSWORD_VERIFIED"));
            assertEquals(0, events(ids.userId(), "PASSWORD_COMPROMISED"));
        } finally { cleanup(ids); }
    }

    @Test
    void providerRunsOutsideSpringTransactionAndReplacedClaimCannotCommit() {
        Ids ids = createUser();
        try {
            UUID id = provisional(ids).getUserCredentialId();
            var claim = claimService.claim(1).getFirst();
            java.util.concurrent.atomic.AtomicBoolean called = new java.util.concurrent.atomic.AtomicBoolean();
            TRANSPORT.set(prefix -> {
                called.set(true);
                assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive(), "HTTP provider must run outside DB transaction");
                jdbc.update("UPDATE user_credentials SET screening_claim_token = ? "
                        + "WHERE user_credential_id = ?", UUID.randomUUID(), id);
                return "";
            });
            assertEquals(DeferredCredentialScreeningService.Outcome.NOT_PENDING,
                    claimedRecovery.resolveClaimed(claim));
            assertTrue(called.get());
            assertEquals("PENDING_VERIFICATION",
                    credentials.findById(id).orElseThrow().getScreeningStatus());
            assertEquals(0, events(ids.userId(), "PASSWORD_VERIFIED"));
        } finally { cleanup(ids); }
    }

    @Test
    void secondProviderFailureIncreasesBackoffWithoutSecurityEvent() {
        Ids ids = createUser();
        try {
            UUID id = provisional(ids).getUserCredentialId();
            TRANSPORT.set(prefix -> { throw new IllegalStateException("provider unavailable"); });
            var first = claimService.claim(1).getFirst();
            assertEquals(DeferredCredentialScreeningService.Outcome.UNAVAILABLE,
                    claimedRecovery.resolveClaimed(first));
            UserCredential afterFirst = credentials.findById(id).orElseThrow();
            assertEquals(1, afterFirst.getScreeningRetryAttempts());
            OffsetDateTime firstNext = afterFirst.getNextScreeningRetryAt();
            assertTrue(claimService.claim(1).isEmpty());
            jdbc.update("UPDATE user_credentials SET next_screening_retry_at = ? "
                    + "WHERE user_credential_id = ?", OffsetDateTime.now().minusSeconds(1), id);
            var second = claimService.claim(1).getFirst();
            assertNotEquals(first.token(), second.token());
            assertEquals(DeferredCredentialScreeningService.Outcome.UNAVAILABLE,
                    claimedRecovery.resolveClaimed(second));
            UserCredential afterSecond = credentials.findById(id).orElseThrow();
            assertEquals(2, afterSecond.getScreeningRetryAttempts());
            assertTrue(afterSecond.getNextScreeningRetryAt().isAfter(firstNext));
            assertEquals(0, events(ids.userId(), "PASSWORD_VERIFIED"));
            assertEquals(0, events(ids.userId(), "PASSWORD_COMPROMISED"));
        } finally { cleanup(ids); }
    }

    @Test
    void newerPasswordGenerationRejectsResultEvenIfPendingStatusRemains() {
        Ids ids = createUser();
        try {
            UUID id = provisional(ids).getUserCredentialId();
            var claim = claimService.claim(1).getFirst();
            TRANSPORT.set(prefix -> {
                jdbc.update("UPDATE user_credentials SET password_hash = ?, "
                        + "version = version + 1 WHERE user_credential_id = ?",
                        "different-argon2id-generation", id);
                return "";
            });
            assertEquals(DeferredCredentialScreeningService.Outcome.NOT_PENDING,
                    claimedRecovery.resolveClaimed(claim));
            assertEquals("different-argon2id-generation",
                    credentials.findById(id).orElseThrow().getPasswordHash());
            assertEquals("PENDING_VERIFICATION",
                    credentials.findById(id).orElseThrow().getScreeningStatus());
            assertEquals(0, events(ids.userId(), "PASSWORD_VERIFIED"));
        } finally { cleanup(ids); }
    }


    @Test
    void schedulerProcessesEligibleCredentialOnceUsingRealPostgresql() {
        Ids ids = createUser();
        try {
            UUID id = provisional(ids).getUserCredentialId();
            var scheduler = new DeferredRecoveryScheduler(coordinator, 25, true);
            scheduler.reconcile();
            scheduler.reconcile();
            assertEquals("VERIFIED", credentials.findById(id).orElseThrow().getScreeningStatus());
            assertEquals(1, events(ids.userId(), "PASSWORD_VERIFIED"));
        } finally { cleanup(ids); }
    }

    @Test
    void concurrentCoordinatorsCannotDoubleFinalizeCredential() throws Exception {
        Ids ids = createUser();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            UUID id = provisional(ids).getUserCredentialId();
            var start = new java.util.concurrent.CountDownLatch(1);
            var first = executor.submit(() -> {
                start.await();
                return coordinator.process(1);
            });
            var second = executor.submit(() -> {
                start.await();
                return coordinator.process(1);
            });
            start.countDown();
            first.get(15, java.util.concurrent.TimeUnit.SECONDS);
            second.get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("VERIFIED", credentials.findById(id).orElseThrow().getScreeningStatus());
            assertEquals(1, events(ids.userId(), "PASSWORD_VERIFIED"));
        } finally {
            executor.shutdownNow();
            cleanup(ids);
        }
    }


    @Test
    void schedulerSkipsCredentialsUntilTheirRetryTime() {
        Ids ids = createUser();
        try {
            UUID id = provisional(ids).getUserCredentialId();
            jdbc.update("UPDATE user_credentials SET next_screening_retry_at = ? "
                    + "WHERE user_credential_id = ?", OffsetDateTime.now().plusMinutes(30), id);
            new DeferredRecoveryScheduler(coordinator, 25, true).reconcile();
            assertEquals("PENDING_VERIFICATION", credentials.findById(id).orElseThrow().getScreeningStatus());
            assertEquals(0, events(ids.userId(), "PASSWORD_VERIFIED"));
            assertNull(jdbc.queryForObject("SELECT screening_claim_token FROM user_credentials "
                    + "WHERE user_credential_id = ?", UUID.class, id));
            jdbc.update("UPDATE user_credentials SET next_screening_retry_at = ? "
                    + "WHERE user_credential_id = ?", OffsetDateTime.now().minusSeconds(1), id);
            new DeferredRecoveryScheduler(coordinator, 25, true).reconcile();
            assertEquals("VERIFIED", credentials.findById(id).orElseThrow().getScreeningStatus());
            assertEquals(1, events(ids.userId(), "PASSWORD_VERIFIED"));
        } finally { cleanup(ids); }
    }

    @Test
    void schedulerSkipsExpiredProvisionalCredential() {
        Ids ids = createUser();
        try {
            UUID id = provisional(ids).getUserCredentialId();
            OffsetDateTime expiredIssuedAt = OffsetDateTime.now().minusHours(13);
            jdbc.update("UPDATE user_credentials SET provisional_issued_at = ?, "
                    + "provisional_expires_at = ?, next_screening_retry_at = ? "
                    + "WHERE user_credential_id = ?", expiredIssuedAt,
                    expiredIssuedAt.plusHours(12), OffsetDateTime.now().minusSeconds(1), id);
            new DeferredRecoveryScheduler(coordinator, 25, true).reconcile();
            assertEquals("PENDING_VERIFICATION", credentials.findById(id).orElseThrow().getScreeningStatus());
            assertEquals(0, events(ids.userId(), "PASSWORD_VERIFIED"));
            assertNull(jdbc.queryForObject("SELECT screening_claim_token FROM user_credentials "
                    + "WHERE user_credential_id = ?", UUID.class, id));
        } finally { cleanup(ids); }
    }

    @Test
    void schedulerReclaimsExpiredLeaseButNeverUnexpiredLease() {
        Ids ids = createUser();
        try {
            UUID id = provisional(ids).getUserCredentialId();
            var old = claimService.claim(1).getFirst();
            new DeferredRecoveryScheduler(coordinator, 25, true).reconcile();
            assertEquals("PENDING_VERIFICATION", credentials.findById(id).orElseThrow().getScreeningStatus());
            assertEquals(old.token(), jdbc.queryForObject("SELECT screening_claim_token "
                    + "FROM user_credentials WHERE user_credential_id = ?", UUID.class, id));
            jdbc.update("UPDATE user_credentials SET screening_claim_expires_at = ? "
                    + "WHERE user_credential_id = ?", OffsetDateTime.now().minusSeconds(1), id);
            new DeferredRecoveryScheduler(coordinator, 25, true).reconcile();
            assertEquals("VERIFIED", credentials.findById(id).orElseThrow().getScreeningStatus());
            assertEquals(1, events(ids.userId(), "PASSWORD_VERIFIED"));
            assertFalse(claimService.release(old));
        } finally { cleanup(ids); }
    }

    @Test
    void schedulerHonorsNinthFailureRetryCap() {
        Ids ids = createUser();
        try {
            UUID id = provisional(ids).getUserCredentialId();
            TRANSPORT.set(prefix -> { throw new IllegalStateException("synthetic outage"); });
            jdbc.update("UPDATE user_credentials SET screening_retry_attempts = 8, "
                    + "next_screening_retry_at = ? WHERE user_credential_id = ?",
                    OffsetDateTime.now().minusSeconds(1), id);
            var scheduler = new DeferredRecoveryScheduler(coordinator, 25, true);
            java.time.Instant beforeNinth = java.time.Instant.now();
            scheduler.reconcile();
            java.time.Instant afterNinthRun = java.time.Instant.now();
            UserCredential afterNinth = credentials.findById(id).orElseThrow();
            assertEquals(9, afterNinth.getScreeningRetryAttempts());
            java.time.Instant ninthRetryAt = afterNinth.getNextScreeningRetryAt().toInstant();
            assertFalse(ninthRetryAt.isBefore(beforeNinth.plus(java.time.Duration.ofMinutes(240))));
            assertFalse(ninthRetryAt.isAfter(afterNinthRun.plus(java.time.Duration.ofMinutes(240))));
            assertTrue(ninthRetryAt.isBefore(afterNinth.getProvisionalExpiresAt().toInstant()));
            scheduler.reconcile();
            assertEquals(9, credentials.findById(id).orElseThrow().getScreeningRetryAttempts());
            assertEquals(0, events(ids.userId(), "PASSWORD_VERIFIED"));
            assertEquals(0, events(ids.userId(), "PASSWORD_COMPROMISED"));
        } finally { cleanup(ids); }
    }

}
