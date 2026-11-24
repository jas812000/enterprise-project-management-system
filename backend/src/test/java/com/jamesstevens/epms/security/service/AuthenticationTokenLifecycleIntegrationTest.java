package com.jamesstevens.epms.security.service;

import com.jamesstevens.epms.organization.dto.CreateOrganizationRequest;
import com.jamesstevens.epms.organization.service.OrganizationService;
import com.jamesstevens.epms.user.dto.CreateUserRequest;
import com.jamesstevens.epms.user.entity.UserCredential;
import com.jamesstevens.epms.user.repository.UserCredentialRepository;
import com.jamesstevens.epms.user.service.UserService;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** B2C acceptance against real PostgreSQL; no production database or clock sleeps. */
@SpringBootTest
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = "epms.security.audit.hmac-key=acceptance-test-only-key-not-for-production-12345")
class AuthenticationTokenLifecycleIntegrationTest {
    private static final UUID HUMAN = UUID.fromString("0199a000-0000-7000-8000-000000000001");
    private static final SecureRandom RANDOM = new SecureRandom();
    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18")
            .withInitScript("test-db-init.sql");
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthenticationTokenDatabaseAdapter tokenDatabaseAdapter;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired OrganizationService organizations;
    @Autowired UserService users;
    @Autowired UserCredentialRepository credentials;
    @Autowired PlatformTransactionManager transactions;
    private UUID userId;

    @BeforeAll
    void eligibleUser() {
        String employee = "B2C-" + UUID.randomUUID().toString().substring(0, 10);
        userId = new TransactionTemplate(transactions).execute(status -> {
            organizations.createOrganization(new CreateOrganizationRequest(
                    "B2C Integration Organization", "Token lifecycle acceptance"));
            var user = users.createUser(new CreateUserRequest(null, null, HUMAN, employee,
                    "Token", "Tester", employee.toLowerCase() + "@example.com"));
            credentials.save(new UserCredential(user.userId(), "encoded-test-hash", "ARGON2ID", false));
            return user.userId();
        });
    }

    private static byte[] hash() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private UUID issue(byte[] access, byte[] renewal) {
        return jdbc.queryForObject("SELECT public.epms_auth_issue(?,?,?)", UUID.class,
                userId, access, renewal);
    }

    private String rotate(byte[] old, byte[] access, byte[] renewal) {
        return jdbc.queryForObject("SELECT public.epms_auth_rotate(?,?,?)", String.class,
                old, access, renewal);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    /** B2D-5C: every database credential-eligibility gate must reject issuance atomically. */
    @Test
    void ineligibleCredentialsCannotIssueTokensOrSuccessfulAudit() {
        String[] disqualifyingUpdates = {
                "UPDATE users SET is_active=FALSE WHERE user_id=?",
                "UPDATE users SET is_archived=TRUE WHERE user_id=?",
                "UPDATE user_credentials SET screening_status='EXPIRED' WHERE user_id=?",
                "UPDATE user_credentials SET must_change_password=TRUE WHERE user_id=?",
                "UPDATE user_credentials SET login_admin_unlock_required=TRUE WHERE user_id=?",
                "UPDATE user_credentials SET login_locked_until=clock_timestamp()+interval '15 minutes' WHERE user_id=?",
                "UPDATE user_credentials SET password_algorithm_code='LEGACY' WHERE user_id=?"
        };
        for (String update : disqualifyingUpdates) {
            byte[] access = hash();
            byte[] renewal = hash();
            int sessionsBefore = count("SELECT count(*) FROM user_auth_sessions WHERE user_id=?", userId);
            int auditBefore = count("SELECT count(*) FROM authentication_security_events WHERE target_user_id=? AND event_type='TOKEN_ISSUED'", userId);
            new TransactionTemplate(transactions).execute(status -> {
                jdbc.update(update, userId);
                assertThrows(org.springframework.dao.DataAccessException.class,
                        () -> issue(access, renewal), "Must reject: " + update);
                status.setRollbackOnly();
                return null;
            });
            assertEquals(sessionsBefore, count("SELECT count(*) FROM user_auth_sessions WHERE user_id=?", userId), update);
            assertEquals(0, count("SELECT count(*) FROM user_access_tokens WHERE token_hash=? OR token_hash=?", access, renewal), update);
            assertEquals(auditBefore, count("SELECT count(*) FROM authentication_security_events WHERE target_user_id=? AND event_type='TOKEN_ISSUED'", userId), update);
        }
    }

    /** B2D-5C: invalid token material cannot create sessions, tokens, or issuance audits. */
    @Test
    void invalidTokenHashesRejectIssuanceAtomically() {
        byte[] valid = hash();
        byte[][] invalid = {null, new byte[0], new byte[31], new byte[33]};
        int sessionsBefore = count("SELECT count(*) FROM user_auth_sessions WHERE user_id=?", userId);
        int auditBefore = count("SELECT count(*) FROM authentication_security_events WHERE target_user_id=? AND event_type='TOKEN_ISSUED'", userId);
        for (byte[] bad : invalid) {
            assertThrows(org.springframework.dao.DataAccessException.class,
                    () -> issue(bad, valid));
            assertThrows(org.springframework.dao.DataAccessException.class,
                    () -> issue(valid, bad));
        }
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> issue(valid, valid), "Access and renewal hashes must differ");
        assertEquals(sessionsBefore, count("SELECT count(*) FROM user_auth_sessions WHERE user_id=?", userId));
        assertEquals(auditBefore, count("SELECT count(*) FROM authentication_security_events WHERE target_user_id=? AND event_type='TOKEN_ISSUED'", userId));
    }

    @Test
    void rotationConsumesOnceAndReplayRevokesSession() {
        byte[] original = hash();
        UUID session = issue(hash(), original);
        int replayAuditBefore = count("SELECT count(*) FROM authentication_security_events WHERE target_user_id=? AND event_type='TOKEN_REPLAY_DETECTED' AND reason='RENEWAL_REPLAY'", userId);
        assertEquals("ROTATED", rotate(original, hash(), hash()));
        assertEquals("REPLAY_REVOKED", rotate(original, hash(), hash()));
        assertEquals(1, count("SELECT count(*) FROM user_auth_sessions WHERE session_id=? AND revoked_at IS NOT NULL AND revocation_reason='RENEWAL_REPLAY'", session));
        assertEquals(replayAuditBefore + 1, count("SELECT count(*) FROM authentication_security_events WHERE target_user_id=? AND event_type='TOKEN_REPLAY_DETECTED' AND reason='RENEWAL_REPLAY'", userId));
    }

    @Test
    void concurrentRotationNeverIssuesTwoSuccesses() throws Exception {
        byte[] original = hash();
        UUID session = issue(hash(), original);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return rotate(original, hash(), hash()); });
            var second = pool.submit(() -> { start.await(); return rotate(original, hash(), hash()); });
            start.countDown();
            String a = first.get(30, TimeUnit.SECONDS);
            String b = second.get(30, TimeUnit.SECONDS);
            assertEquals(1, ("ROTATED".equals(a) ? 1 : 0) + ("ROTATED".equals(b) ? 1 : 0));
            assertTrue("REPLAY_REVOKED".equals(a) || "REPLAY_REVOKED".equals(b));
            assertEquals(1, count("SELECT count(*) FROM user_auth_sessions WHERE session_id=? AND revoked_at IS NOT NULL", session));
        }
    }

    @Test
    void rollbackOfIssuanceLeavesNoSessionOrAudit() {
        byte[] access = hash();
        byte[] renewal = hash();
        UUID session = new TransactionTemplate(transactions).execute(status -> {
            UUID issued = issue(access, renewal);
            status.setRollbackOnly();
            return issued;
        });
        assertEquals(0, count("SELECT count(*) FROM user_auth_sessions WHERE session_id=?", session));
        assertEquals(0, count("SELECT count(*) FROM user_access_tokens WHERE token_hash=?", access));
    }

    @Test
    void idleAndAbsoluteDeadlinesRejectRenewal() {
        byte[] idleToken = hash();
        UUID idleSession = issue(hash(), idleToken);
        jdbc.update("UPDATE user_auth_sessions SET created_at=clock_timestamp()-interval '40 minutes', last_activity_at=clock_timestamp()-interval '31 minutes', idle_expires_at=clock_timestamp()-interval '1 minute', absolute_expires_at=clock_timestamp()+interval '6 days' WHERE session_id=?", idleSession);
        assertEquals("EXPIRED", rotate(idleToken, hash(), hash()));
        byte[] absoluteToken = hash();
        UUID absoluteSession = issue(hash(), absoluteToken);
        jdbc.update("UPDATE user_auth_sessions SET created_at=statement_timestamp()-interval '8 days', last_activity_at=statement_timestamp()-interval '31 minutes', idle_expires_at=statement_timestamp()-interval '1 minute', absolute_expires_at=statement_timestamp()-interval '1 day' WHERE session_id=?", absoluteSession);
        assertEquals("EXPIRED", rotate(absoluteToken, hash(), hash()));
    }

    @Test
    void passwordChangeInvalidatesRenewal() {
        byte[] renewal = hash();
        issue(hash(), renewal);
        jdbc.update("UPDATE user_credentials SET password_changed_at=clock_timestamp()+interval '1 second' WHERE user_id=?", userId);
        assertEquals("REJECTED", rotate(renewal, hash(), hash()));
    }

    @Test
    void runtimeCannotMutateTokenTablesOrCallAsOtherRoles() {
        assertEquals(Boolean.FALSE, jdbc.queryForObject("SELECT has_table_privilege('epms_app','public.user_access_tokens','UPDATE')", Boolean.class));
        assertEquals(Boolean.FALSE, jdbc.queryForObject("SELECT has_table_privilege('epms_app','public.user_auth_sessions','INSERT')", Boolean.class));
        assertEquals(Boolean.FALSE, jdbc.queryForObject("SELECT has_function_privilege('epms_rbac_runtime','public.epms_auth_rotate(bytea,bytea,bytea)','EXECUTE')", Boolean.class));
        assertEquals(Boolean.FALSE, jdbc.queryForObject("SELECT has_function_privilege('epms_bootstrap_runtime','public.epms_auth_revoke(uuid,text)','EXECUTE')", Boolean.class));
    }


    /** B2D-2: a competing issuance must wait for the per-user credential lock. */
    @Test
    void issuanceWaitsForCredentialWriterAndReadsCommittedVersion() throws Exception {
        try (var holder = dataSource.getConnection();
             var pool = Executors.newSingleThreadExecutor()) {
            holder.setAutoCommit(false);
            try {
                acquireUserLock(holder, userId);
                var future = pool.submit(() -> issue(hash(), hash()));
                awaitAdvisoryWait(10);
                try (var update = holder.prepareStatement(
                        "UPDATE user_credentials SET password_changed_at=clock_timestamp() + interval '2 seconds' WHERE user_id=?")) {
                    update.setObject(1, userId);
                    assertEquals(1, update.executeUpdate());
                }
                holder.commit();
                UUID session = future.get(20, TimeUnit.SECONDS);
                assertEquals(1, count("SELECT count(*) FROM user_auth_sessions s JOIN user_credentials c ON c.user_id=s.user_id WHERE s.session_id=? AND s.credential_version=c.password_changed_at", session));
            } finally {
                holder.rollback();
            }
        }
    }

    /** B2D-2: a credential writer cannot acquire the user lock while issuance owns it. */
    @Test
    void credentialWriterWaitsForExistingUserLock() throws Exception {
        try (var holder = dataSource.getConnection();
             var pool = Executors.newSingleThreadExecutor()) {
            holder.setAutoCommit(false);
            try {
                acquireUserLock(holder, userId);
                var future = pool.submit(() -> {
                    try (var writer = dataSource.getConnection()) {
                        writer.setAutoCommit(false);
                        try {
                            acquireUserLock(writer, userId);
                            try (var update = writer.prepareStatement(
                                    "UPDATE user_credentials SET password_changed_at=clock_timestamp() + interval '3 seconds' WHERE user_id=?")) {
                                update.setObject(1, userId);
                                int changed = update.executeUpdate();
                                writer.commit();
                                return changed;
                            }
                        } catch (Exception failure) {
                            writer.rollback();
                            throw failure;
                        }
                    }
                });
                awaitAdvisoryWait(10);
                holder.commit();
                assertEquals(1, future.get(20, TimeUnit.SECONDS));
            } finally {
                holder.rollback();
            }
        }
    }

    /** B2D-2: transaction rollback releases the advisory lock. */
    @Test
    void rolledBackCredentialLockReleasesWaitingIssuance() throws Exception {
        try (var holder = dataSource.getConnection();
             var pool = Executors.newSingleThreadExecutor()) {
            holder.setAutoCommit(false);
            try {
                acquireUserLock(holder, userId);
                var future = pool.submit(() -> issue(hash(), hash()));
                awaitAdvisoryWait(10);
                holder.rollback();
                assertNotNull(future.get(20, TimeUnit.SECONDS));
            } finally {
                holder.rollback();
            }
        }
    }

    /** B2D-2: the per-user lock key must not serialize unrelated UUIDs. */
    @Test
    void unrelatedUserAdvisoryLockDoesNotConflict() throws Exception {
        UUID unrelated = UUID.randomUUID();
        try (var holder = dataSource.getConnection();
             var other = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            other.setAutoCommit(false);
            try {
                acquireUserLock(holder, userId);
                try (var statement = other.prepareStatement(
                        "SELECT pg_catalog.pg_try_advisory_xact_lock(pg_catalog.hashtextextended(CAST(? AS text), 82302026))")) {
                    statement.setString(1, unrelated.toString());
                    try (var result = statement.executeQuery()) {
                        assertTrue(result.next());
                        assertTrue(result.getBoolean(1));
                    }
                }
            } finally {
                holder.rollback();
                other.rollback();
            }
        }
    }

    /** B2D-4B: a concurrent account lockout must prevent token issuance. */
    @Test
    void issuanceWaitsForCommittedLockoutAndDoesNotCreateSession() throws Exception {
        byte[] access = hash();
        byte[] renewal = hash();
        try (var holder = dataSource.getConnection();
             var pool = Executors.newSingleThreadExecutor()) {
            holder.setAutoCommit(false);
            try {
                acquireUserLock(holder, userId);
                var future = pool.submit(() -> {
                    try {
                        return issue(access, renewal);
                    } catch (org.springframework.dao.DataAccessException rejected) {
                        return null;
                    }
                });
                awaitAdvisoryWait(10);
                try (var update = holder.prepareStatement(
                        "UPDATE user_credentials SET login_locked_until=clock_timestamp()+interval '15 minutes' WHERE user_id=?")) {
                    update.setObject(1, userId);
                    assertEquals(1, update.executeUpdate());
                }
                holder.commit();
                assertNull(future.get(20, TimeUnit.SECONDS),
                        "Issuance must reject credentials locked by a concurrent writer");
                assertEquals(0, count("SELECT count(*) FROM user_access_tokens WHERE token_hash=?", access));
            } finally {
                holder.rollback();
                jdbc.update("UPDATE user_credentials SET login_locked_until=NULL WHERE user_id=?", userId);
            }
        }
    }

    /** B2D-4B: a committed administrative lockout guard rejects fresh issuance. */
    @Test
    void administrativeUnlockRequiredRejectsTokenIssuance() {
        byte[] access = hash();
        try {
            jdbc.update("UPDATE user_credentials SET login_admin_unlock_required=TRUE WHERE user_id=?", userId);
            assertThrows(org.springframework.dao.DataAccessException.class,
                    () -> issue(access, hash()));
            assertEquals(0, count("SELECT count(*) FROM user_access_tokens WHERE token_hash=?", access));
        } finally {
            jdbc.update("UPDATE user_credentials SET login_admin_unlock_required=FALSE WHERE user_id=?", userId);
        }
    }


    /** B2D-4C: test-only trigger injects an error at the actual mandatory audit INSERT. */
    private void withFailingTokenAudit(String eventType, Runnable action) {
        jdbc.execute("""
                CREATE FUNCTION public.epms_test_reject_token_audit() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                  IF NEW.event_type = current_setting('epms.test.reject_token_audit', true)
                  THEN RAISE EXCEPTION 'B2D-4C injected mandatory token audit failure'; END IF;
                  RETURN NEW;
                END $$
                """);
        try {
            jdbc.execute("""
                    CREATE TRIGGER epms_test_reject_token_audit
                    BEFORE INSERT ON public.authentication_security_events
                    FOR EACH ROW EXECUTE FUNCTION public.epms_test_reject_token_audit()
                    """);
            try {
                assertThrows(org.springframework.dao.DataAccessException.class,
                        () -> new TransactionTemplate(transactions).execute(status -> {
                            jdbc.queryForObject("SELECT set_config('epms.test.reject_token_audit', ?, true)",
                                    String.class, eventType);
                            action.run();
                            return null;
                        }));
            } finally {
                jdbc.execute("DROP TRIGGER IF EXISTS epms_test_reject_token_audit " +
                        "ON public.authentication_security_events");
            }
        } finally {
            jdbc.execute("DROP FUNCTION IF EXISTS public.epms_test_reject_token_audit()");
        }
    }

    @Test
    void mandatoryIssuanceAuditFailureRollsBackSessionAndTokens() {
        byte[] access = hash();
        byte[] renewal = hash();
        int sessionsBefore = count("SELECT count(*) FROM user_auth_sessions WHERE user_id=?", userId);
        int auditsBefore = count("SELECT count(*) FROM authentication_security_events " +
                "WHERE target_user_id=? AND event_type='TOKEN_ISSUED'", userId);
        withFailingTokenAudit("TOKEN_ISSUED", () -> issue(access, renewal));
        assertEquals(sessionsBefore, count("SELECT count(*) FROM user_auth_sessions WHERE user_id=?", userId));
        assertEquals(0, count("SELECT count(*) FROM user_access_tokens WHERE token_hash IN (?,?)", access, renewal));
        assertEquals(auditsBefore, count("SELECT count(*) FROM authentication_security_events " +
                "WHERE target_user_id=? AND event_type='TOKEN_ISSUED'", userId));
    }

    @Test
    void mandatoryRenewalAuditFailurePreservesOriginalRenewal() {
        byte[] original = hash();
        UUID session = issue(hash(), original);
        byte[] nextAccess = hash();
        byte[] nextRenewal = hash();
        int auditsBefore = count("SELECT count(*) FROM authentication_security_events " +
                "WHERE target_user_id=? AND event_type='TOKEN_RENEWED'", userId);
        withFailingTokenAudit("TOKEN_RENEWED", () -> rotate(original, nextAccess, nextRenewal));
        assertEquals(0, count("SELECT count(*) FROM user_access_tokens WHERE token_hash IN (?,?)", nextAccess, nextRenewal));
        assertEquals(1, count("SELECT count(*) FROM user_access_tokens " +
                "WHERE session_id=? AND token_hash=? AND consumed_at IS NULL AND revoked_at IS NULL", session, original));
        assertEquals(auditsBefore, count("SELECT count(*) FROM authentication_security_events " +
                "WHERE target_user_id=? AND event_type='TOKEN_RENEWED'", userId));
        assertEquals("ROTATED", rotate(original, hash(), hash()), "Original renewal must remain usable");
    }

    @Test
    void mandatoryReplayAuditFailureDoesNotPartiallyRevokeSession() {
        byte[] original = hash();
        UUID session = issue(hash(), original);
        assertEquals("ROTATED", rotate(original, hash(), hash()));
        int auditsBefore = count("SELECT count(*) FROM authentication_security_events " +
                "WHERE target_user_id=? AND event_type='TOKEN_REPLAY_DETECTED'", userId);
        withFailingTokenAudit("TOKEN_REPLAY_DETECTED", () -> rotate(original, hash(), hash()));
        assertEquals(1, count("SELECT count(*) FROM user_auth_sessions WHERE session_id=? AND revoked_at IS NULL", session));
        assertEquals(0, count("SELECT count(*) FROM user_access_tokens WHERE session_id=? AND revoked_at IS NOT NULL", session));
        assertEquals(auditsBefore, count("SELECT count(*) FROM authentication_security_events " +
                "WHERE target_user_id=? AND event_type='TOKEN_REPLAY_DETECTED'", userId));
        assertEquals("REPLAY_REVOKED", rotate(original, hash(), hash()), "Replay handling must recover after audit restoration");
    }

    @Test
    void mandatoryRevocationAuditFailurePreservesActiveSessionAndTokens() {
        byte[] access = hash();
        byte[] renewal = hash();
        UUID session = issue(access, renewal);
        int auditsBefore = count("SELECT count(*) FROM authentication_security_events " +
                "WHERE target_user_id=? AND event_type='SESSION_REVOKED'", userId);
        withFailingTokenAudit("SESSION_REVOKED", () -> jdbc.queryForObject(
                "SELECT public.epms_auth_revoke(?, 'LOGOUT')", Boolean.class, session));
        assertEquals(1, count("SELECT count(*) FROM user_auth_sessions WHERE session_id=? AND revoked_at IS NULL", session));
        assertEquals(2, count("SELECT count(*) FROM user_access_tokens WHERE session_id=? AND revoked_at IS NULL", session));
        assertEquals(auditsBefore, count("SELECT count(*) FROM authentication_security_events " +
                "WHERE target_user_id=? AND event_type='SESSION_REVOKED'", userId));
        assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT public.epms_auth_revoke(?, 'LOGOUT')", Boolean.class, session));
    }


    /** B2D-4D: database-clock renewal expiration, with no wall-clock sleeps. */
    @Test
    void renewalExpirationBoundaryUsesDatabaseClockAndPreservesState() {
        byte[] before = hash();
        UUID beforeSession = issue(hash(), before);
        jdbc.update("""
                UPDATE user_access_tokens
                SET expires_at=clock_timestamp()+interval '10 seconds'
                WHERE session_id=? AND token_type='RENEWAL' AND token_hash=?
                """, beforeSession, before);
        assertEquals("ROTATED", rotate(before, hash(), hash()),
                "A renewal token before its expiration must rotate");

        byte[] expired = hash();
        UUID expiredSession = issue(hash(), expired);
        jdbc.update("""
                UPDATE user_access_tokens
                SET expires_at=clock_timestamp()-interval '1 microsecond'
                WHERE session_id=? AND token_type='RENEWAL' AND token_hash=?
                """, expiredSession, expired);
        assertEquals("EXPIRED", rotate(expired, hash(), hash()));
        assertEquals(1, count("""
                SELECT count(*) FROM user_access_tokens
                WHERE session_id=? AND token_hash=? AND consumed_at IS NULL
                """, expiredSession, expired));
        assertEquals(1, count("""
                SELECT count(*) FROM user_auth_sessions
                WHERE session_id=? AND revoked_at IS NULL
                """, expiredSession));
    }

    /** B2D-4D: idle deadline takes effect without mutating the presented token. */
    @Test
    void idleExpirationBoundaryUsesDatabaseClockAndPreservesState() {
        byte[] before = hash();
        UUID beforeSession = issue(hash(), before);
        jdbc.update("""
                UPDATE user_auth_sessions
                SET idle_expires_at=clock_timestamp()+interval '10 seconds'
                WHERE session_id=?
                """, beforeSession);
        assertEquals("ROTATED", rotate(before, hash(), hash()));

        byte[] expired = hash();
        UUID expiredSession = issue(hash(), expired);
        jdbc.update("""
                UPDATE user_auth_sessions
                SET idle_expires_at=clock_timestamp()-interval '1 microsecond'
                WHERE session_id=?
                """, expiredSession);
        assertEquals("EXPIRED", rotate(expired, hash(), hash()));
        assertEquals(1, count("""
                SELECT count(*) FROM user_access_tokens
                WHERE session_id=? AND token_hash=? AND consumed_at IS NULL
                """, expiredSession, expired));
        assertEquals(1, count("""
                SELECT count(*) FROM user_auth_sessions
                WHERE session_id=? AND revoked_at IS NULL
                """, expiredSession));
    }

    /** B2D-4D: absolute session expiry also bounds replacement token expiry. */
    @Test
    void absoluteExpirationBoundaryAndReplacementCapUseDatabaseClock() {
        byte[] before = hash();
        UUID beforeSession = issue(hash(), before);
        jdbc.update("""
                UPDATE user_auth_sessions
                SET absolute_expires_at=clock_timestamp()+interval '10 seconds'
                WHERE session_id=?
                """, beforeSession);
        byte[] nextAccess = hash();
        byte[] nextRenewal = hash();
        assertEquals("ROTATED", rotate(before, nextAccess, nextRenewal));
        assertEquals(2, count("""
                SELECT count(*) FROM user_access_tokens t
                JOIN user_auth_sessions s ON s.session_id=t.session_id
                WHERE s.session_id=? AND t.token_hash IN (?,?)
                  AND t.expires_at<=s.absolute_expires_at
                """, beforeSession, nextAccess, nextRenewal));

        byte[] expired = hash();
        UUID expiredSession = issue(hash(), expired);
        jdbc.update("""
                UPDATE user_auth_sessions
                SET absolute_expires_at=clock_timestamp()-interval '1 microsecond'
                WHERE session_id=?
                """, expiredSession);
        assertEquals("EXPIRED", rotate(expired, hash(), hash()));
        assertEquals(1, count("""
                SELECT count(*) FROM user_access_tokens
                WHERE session_id=? AND token_hash=? AND consumed_at IS NULL
                """, expiredSession, expired));
        assertEquals(1, count("""
                SELECT count(*) FROM user_auth_sessions
                WHERE session_id=? AND revoked_at IS NULL
                """, expiredSession));
    }

    /** B2D-4D: access tokens have a 15-minute deadline at issuance. */
    @Test
    void issuedAccessTokenHasFifteenMinuteDatabaseDeadline() {
        byte[] access = hash();
        UUID session = issue(access, hash());
        assertEquals(1, count("""
                SELECT count(*) FROM user_access_tokens
                WHERE session_id=? AND token_hash=? AND token_type='ACCESS'
                  AND expires_at=issued_at+interval '15 minutes'
                """, session, access));
    }

    private void acquireUserLock(java.sql.Connection connection, UUID user) throws Exception {
        try (var statement = connection.prepareStatement(
                "SELECT pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(CAST(? AS text), 82302026))")) {
            statement.setString(1, user.toString());
            statement.execute();
        }
    }

    /** Observe an actual PostgreSQL advisory-lock wait, not an arbitrary timing delay. */
    private void awaitAdvisoryWait(int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_catalog.pg_stat_activity " +
                    "WHERE datname=current_database() AND wait_event_type='Lock' AND wait_event='advisory'",
                    Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(25);
        }
        fail("Expected a competing transaction to wait on a PostgreSQL advisory lock");
    }


    /** B3-A: Spring JDBC adapter must invoke the protected function and persist only digests. */
    @Test
    void javaAdapterIssuesSessionWithOnlyHashedTokenMaterial() {
        AuthenticationTokenMaterial material = new AuthenticationTokenMaterial();
        var access = material.generate();
        var renewal = material.generate();
        UUID session = tokenDatabaseAdapter.issueAuthenticated(userId, access.hash(), renewal.hash());
        assertNotNull(session);
        assertEquals(1, count("SELECT count(*) FROM user_auth_sessions WHERE session_id=? AND user_id=?", session, userId));
        assertEquals(1, count("SELECT count(*) FROM user_access_tokens WHERE session_id=? AND token_type='ACCESS' AND token_hash=?", session, access.hash()));
        assertEquals(1, count("SELECT count(*) FROM user_access_tokens WHERE session_id=? AND token_type='RENEWAL' AND token_hash=?", session, renewal.hash()));
        assertEquals(0, count("SELECT count(*) FROM user_access_tokens WHERE session_id=? AND (token_hash=? OR token_hash=?)", session,
                java.util.Base64.getUrlDecoder().decode(access.value()),
                java.util.Base64.getUrlDecoder().decode(renewal.value())));
    }

    /** B3-A: invalid digests are rejected by Java before the PostgreSQL function executes. */
    @Test
    void javaAdapterRejectsMalformedDigestsWithoutCreatingSession() {
        int before = count("SELECT count(*) FROM user_auth_sessions WHERE user_id=?", userId);
        org.springframework.dao.InvalidDataAccessApiUsageException malformed = assertThrows(
                org.springframework.dao.InvalidDataAccessApiUsageException.class,
                () -> tokenDatabaseAdapter.issueAuthenticated(userId, new byte[31], hash()));
        assertInstanceOf(IllegalArgumentException.class, malformed.getCause());
        org.springframework.dao.InvalidDataAccessApiUsageException identical = assertThrows(
                org.springframework.dao.InvalidDataAccessApiUsageException.class,
                () -> tokenDatabaseAdapter.issueAuthenticated(userId, new byte[32], new byte[32]));
        assertInstanceOf(IllegalArgumentException.class, identical.getCause());
        assertEquals(before, count("SELECT count(*) FROM user_auth_sessions WHERE user_id=?", userId));
    }

    /** B3-A: PostgreSQL eligibility failure propagates through the adapter without partial writes. */
    @Test
    void javaAdapterPropagatesIneligibleAccountRejectionAtomically() {
        byte[] access = hash();
        byte[] renewal = hash();
        int before = count("SELECT count(*) FROM user_auth_sessions WHERE user_id=?", userId);
        new TransactionTemplate(transactions).execute(status -> {
            jdbc.update("UPDATE users SET is_active=FALSE WHERE user_id=?", userId);
            assertThrows(org.springframework.dao.DataAccessException.class,
                    () -> tokenDatabaseAdapter.issueAuthenticated(userId, access, renewal));
            status.setRollbackOnly();
            return null;
        });
        assertEquals(before, count("SELECT count(*) FROM user_auth_sessions WHERE user_id=?", userId));
        assertEquals(0, count("SELECT count(*) FROM user_access_tokens WHERE token_hash=? OR token_hash=?", access, renewal));
    }


    /** B5: access validation uses protected function and extends idle deadline only on success. */
    @Test
    void accessValidationSuccessAndRejectedTokenStates() {
        byte[] access = hash();
        UUID session = issue(access, hash());
        assertEquals(userId, tokenDatabaseAdapter.validateAccessDigest(access));
        assertNull(tokenDatabaseAdapter.validateAccessDigest(hash()));
        jdbc.update("UPDATE user_access_tokens SET revoked_at=clock_timestamp() WHERE session_id=? AND token_hash=?", session, access);
        assertNull(tokenDatabaseAdapter.validateAccessDigest(access));
    }

    @Test
    void accessValidationRejectsExpiredTokenAndSessionDeadlines() {
        byte[] expiredToken = hash();
        UUID first = issue(expiredToken, hash());
        jdbc.update("UPDATE user_access_tokens SET issued_at=clock_timestamp()-interval '20 minutes', expires_at=clock_timestamp()-interval '1 second' WHERE session_id=? AND token_hash=?", first, expiredToken);
        assertNull(tokenDatabaseAdapter.validateAccessDigest(expiredToken));
        byte[] idleToken = hash();
        UUID second = issue(idleToken, hash());
        jdbc.update("UPDATE user_auth_sessions SET created_at=clock_timestamp()-interval '3 seconds', last_activity_at=clock_timestamp()-interval '2 seconds', idle_expires_at=clock_timestamp()-interval '1 second', absolute_expires_at=clock_timestamp()+interval '6 days' WHERE session_id=?", second);
        assertNull(tokenDatabaseAdapter.validateAccessDigest(idleToken));
        byte[] absoluteToken = hash();
        UUID third = issue(absoluteToken, hash());
        jdbc.update("UPDATE user_auth_sessions SET created_at=clock_timestamp()-interval '7 days', last_activity_at=clock_timestamp()-interval '2 seconds', idle_expires_at=clock_timestamp()+interval '1 minute', absolute_expires_at=clock_timestamp()-interval '1 second' WHERE session_id=?", third);
        assertNull(tokenDatabaseAdapter.validateAccessDigest(absoluteToken));
    }

    @Test
    void accessValidationRejectsRevokedSessionAndCredentialChange() {
        byte[] revoked = hash();
        UUID session = issue(revoked, hash());
        assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT public.epms_auth_revoke(?, 'LOGOUT')", Boolean.class, session));
        assertNull(tokenDatabaseAdapter.validateAccessDigest(revoked));
        byte[] changed = hash();
        UUID changedSession = issue(changed, hash());
        new TransactionTemplate(transactions).execute(status -> {
            jdbc.update("UPDATE user_credentials SET password_changed_at=clock_timestamp()+interval '1 second' WHERE user_id=?", userId);
            assertNull(tokenDatabaseAdapter.validateAccessDigest(changed));
            status.setRollbackOnly();
            return null;
        });
        assertEquals(userId, tokenDatabaseAdapter.validateAccessDigest(changed));
    }


    /** B5-C: revocation commits while validation waits on the session row. */
    @Test
    void accessValidationWaitsForRevocationAndRejectsCommittedRevocation() throws Exception {
        byte[] access = hash();
        UUID session = issue(access, hash());
        try (var holder = dataSource.getConnection();
             var pool = Executors.newSingleThreadExecutor()) {
            holder.setAutoCommit(false);
            try {
                try (var lock = holder.prepareStatement(
                        "SELECT session_id FROM user_auth_sessions WHERE session_id=? FOR UPDATE")) {
                    lock.setObject(1, session);
                    try (var rows = lock.executeQuery()) { assertTrue(rows.next()); }
                }
                var future = pool.submit(() -> tokenDatabaseAdapter.validateAccessDigest(access));
                awaitSessionLockWait(10);
                try (var revoke = holder.prepareStatement(
                        "SELECT public.epms_auth_revoke(?, 'LOGOUT')")) {
                    revoke.setObject(1, session);
                    try (var rows = revoke.executeQuery()) {
                        assertTrue(rows.next());
                        assertTrue(rows.getBoolean(1));
                    }
                }
                holder.commit();
                assertNull(future.get(20, TimeUnit.SECONDS));
                assertEquals(1, count("SELECT count(*) FROM user_auth_sessions WHERE session_id=? AND revoked_at IS NOT NULL", session));
            } finally {
                holder.rollback();
            }
        }
    }

    /** B5-C: renewal preserves existing access tokens under session-lock contention. */
    @Test
    void accessValidationWaitsForRotationAndAcceptsExistingAccessToken() throws Exception {
        byte[] access = hash();
        byte[] renewal = hash();
        UUID session = issue(access, renewal);
        try (var holder = dataSource.getConnection();
             var pool = Executors.newSingleThreadExecutor()) {
            holder.setAutoCommit(false);
            try {
                try (var lock = holder.prepareStatement(
                        "SELECT session_id FROM user_auth_sessions WHERE session_id=? FOR UPDATE")) {
                    lock.setObject(1, session);
                    try (var rows = lock.executeQuery()) { assertTrue(rows.next()); }
                }
                var future = pool.submit(() -> tokenDatabaseAdapter.validateAccessDigest(access));
                awaitSessionLockWait(10);
                try (var rotation = holder.prepareStatement("SELECT public.epms_auth_rotate(?,?,?)")) {
                    rotation.setBytes(1, renewal);
                    rotation.setBytes(2, hash());
                    rotation.setBytes(3, hash());
                    try (var rows = rotation.executeQuery()) {
                        assertTrue(rows.next());
                        assertEquals("ROTATED", rows.getString(1));
                    }
                }
                holder.commit();
                assertEquals(userId, future.get(20, TimeUnit.SECONDS));
            } finally {
                holder.rollback();
            }
        }
    }

    /** Wait for an actual PostgreSQL row-lock conflict, not a timed race. */
    private void awaitSessionLockWait(int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_catalog.pg_stat_activity " +
                    "WHERE datname=current_database() AND wait_event_type='Lock' " +
                    "AND wait_event IN ('transactionid','tuple')", Integer.class);
            if (waiting != null && waiting > 0) return;
            Thread.sleep(25);
        }
        fail("Expected validation to block on the session row lock");
    }

}
