package com.jamesstevens.epms.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jamesstevens.epms.team.dto.TeamProfileChange;
import com.jamesstevens.epms.team.dto.TeamSecurityAlert;
import com.jamesstevens.epms.team.service.TeamAccountLifecycleService;
import com.jamesstevens.epms.team.service.TeamNotificationAutomation;
import com.jamesstevens.epms.team.service.TeamNotificationService;
import com.jamesstevens.epms.team.service.TeamOperationsService;
import com.jamesstevens.epms.team.service.TeamProfileService;
import java.sql.Connection;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** System dispatch, durable retry, concurrency and escalation against disposable PostgreSQL. */
@SpringBootTest(properties = {"epms.team.notifications.automatic.enabled=true",
        "epms.team.notifications.automatic.initial-delay-ms=600000",
        "epms.team.notifications.automatic.base-retry-seconds=300",
        "epms.team.notifications.automatic.max-retry-seconds=300"})
@Testcontainers
class TeamNotificationAutomationIntegrationTest {
    private static final String ADMIN_EMAIL = "notification-admin@example.invalid";
    private static final String LEADER_EMAIL = "notification-leader@example.invalid";
    private static final String MEMBER_EMAIL = "notification-member@example.invalid";
    private static final String SECRET_REASON = "Confidential emergency fixture reason";
    private static final int PAGE_SIZE = 100;
    private static final int SHORT_DELAY = 1;
    private static final int RETRY_DELAY = 300;
    private static final int MAX_ATTEMPTS = 2;
    private static final int CONCURRENT_TIMEOUT_SECONDS = 15;

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18")
            .withInitScript("rbac-test-db-init.sql");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("epms.rbac.enabled", () -> "true");
        registry.add("epms.rbac.jdbc-url", postgres::getJdbcUrl);
        registry.add("epms.rbac.username", () -> "epms_rbac_runtime");
        registry.add("epms.rbac.password", () -> "rbac-test-only-local-container-password");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired TeamProfileService profiles;
    @Autowired TeamOperationsService operations;
    @Autowired TeamAccountLifecycleService accounts;
    @Autowired TeamNotificationService notifications;
    @Autowired TeamNotificationAutomation automation;
    @Autowired ScheduledTaskHolder scheduledTasks;
    @Autowired JdbcTemplate fixtures;
    @Autowired @Qualifier("rbacJdbcTemplate") JdbcTemplate restricted;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void systemDispatchRetriesWithoutImpersonationAndEscalatesUntilResolution() throws Exception {
        UUID org = fixtures.queryForObject("""
                INSERT INTO public.organizations(name) VALUES ('Automation integration') RETURNING organization_id
                """, UUID.class);
        createUser(org, ADMIN_EMAIL, "SYSTEM_ADMIN");
        UUID leader = createUser(org, LEADER_EMAIL, "MEMBER");
        UUID member = createUser(org, MEMBER_EMAIL, "MEMBER");
        authenticate(ADMIN_EMAIL);
        UUID team = profiles.create(new TeamProfileChange("Automation Team", null, false, "Create"));
        installFailureTrigger();
        operations.joinMember(team, member, "Member appointment");
        SecurityContextHolder.clearContext();
        verifyRetryAndConcurrentRecovery();
        authenticate(ADMIN_EMAIL);
        UUID leaderMembership = verifyAutomaticRecovery(team, leader);
        verifyExhaustionAndRecovery(team, member);
        UUID assignment = operations.assignPermanent(team, leaderMembership, "Appoint leader");
        operations.transition(team, "ACTIVE", "Activate");
        emergencyDuringDispatchLock(team, assignment);
        assertEquals("INACTIVE", profiles.get(team).lifecycleStatus());
        assertTrue(Boolean.TRUE.equals(fixtures.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.team_leadership_assignments
                WHERE team_leadership_assignment_id=? AND ended_at IS NOT NULL)
                """, Boolean.class, assignment)));
        assertTrue(Boolean.TRUE.equals(fixtures.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.team_leadership_succession_events
                WHERE team_id=? AND outcome='NO_ELIGIBLE_SUCCESSOR' AND incoming_assignment_id IS NULL)
                """, Boolean.class, team)));
        assertTrue(Boolean.TRUE.equals(fixtures.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.team_security_alerts a
                JOIN public.team_operation_events e ON e.event_id=a.event_id
                WHERE e.team_id=? AND a.alert_code='NO_ELIGIBLE_SUCCESSOR')
                """, Boolean.class, team)));
        verifyEscalation(team, leaderMembership);
        verifyAccountDuringDispatch(team, leader, member);
        verifyRestrictedPrivileges();
    }

    private void emergencyDuringDispatchLock(UUID team, UUID assignment) throws Exception {
        int dispatches = count("SELECT count(*) FROM public.team_notification_dispatches");
        int attempts = count("SELECT count(*) FROM public.team_notification_attempts");
        // Keep a real dispatch uncommitted on the restricted connection, including its foreign-key locks.
        try (Connection blocker = Objects.requireNonNull(restricted.getDataSource()).getConnection()) {
            blocker.setAutoCommit(false);
            try (PreparedStatement lock = blocker.prepareStatement(
                    "SELECT public.epms_team_system_dispatch(?, ?, ?)")) {
                lock.setInt(1, MAX_ATTEMPTS);
                lock.setInt(2, RETRY_DELAY);
                lock.setInt(3, RETRY_DELAY);
                try (var result = lock.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals(1, result.getInt(1));
                }
                assertEquals(dispatches, count("SELECT count(*) FROM public.team_notification_dispatches"));
                try (var workers = Executors.newFixedThreadPool(2)) {
                    try {
                        var dispatch = workers.submit(() -> systemDispatch(RETRY_DELAY));
                        assertEquals(0, dispatch.get(CONCURRENT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
                        var emergency = workers.submit(() -> {
                            authenticate(ADMIN_EMAIL);
                            try {
                                return operations.emergencyLeadership(team, assignment,
                                        "EMERGENCY_UNAVAILABILITY", SECRET_REASON);
                            } finally {
                                SecurityContextHolder.clearContext();
                            }
                        });
                        assertNull(emergency.get(CONCURRENT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
                    } finally {
                        blocker.rollback();
                    }
                }
            }
        }
        // The interrupted transaction must leave no marker or attempt. Later dispatch recovers the source.
        assertEquals(dispatches, count("SELECT count(*) FROM public.team_notification_dispatches"));
        assertEquals(attempts, count("SELECT count(*) FROM public.team_notification_attempts"));
    }

    private void installFailureTrigger() {
        fixtures.execute("""
                CREATE FUNCTION public.epms_test_notification_failure() RETURNS trigger
                LANGUAGE plpgsql AS $$ BEGIN
                    RAISE EXCEPTION 'Injected confidential failure' USING ERRCODE='23514';
                END $$
                """);
        fixtures.execute("""
                CREATE TRIGGER trg_test_notification_failure BEFORE INSERT ON public.team_notifications
                FOR EACH ROW EXECUTE FUNCTION public.epms_test_notification_failure()
                """);
    }

    private void verifyRetryAndConcurrentRecovery() throws Exception {
        assertTrue(scheduledTasks.getScheduledTasks().size() >= 2);
        try {
            automation.processPending();
            assertEquals(0, count("SELECT count(*) FROM public.team_notification_dispatches"));
            assertEquals(1, count("SELECT count(*) FROM public.team_notification_attempts WHERE outcome='FAILED'"));
            assertEquals(0, systemDispatch(RETRY_DELAY));
            authenticate(ADMIN_EMAIL);
            var failure = notifications.failures(PAGE_SIZE, 0).getFirst();
            assertEquals("23514", failure.failureSqlstate());
            assertNotNull(failure.nextRetryAt());
            assertFalse(failure.automaticExhausted());
            authenticate(MEMBER_EMAIL);
            assertThrows(AccessDeniedException.class, () -> notifications.failures(PAGE_SIZE, 0));
            SecurityContextHolder.clearContext();
        } finally {
            removeFailureTrigger();
        }
        // Human recovery can explicitly bypass the retry delay, but must remain authorized.
        authenticate(ADMIN_EMAIL);
        assertEquals(1, notifications.dispatch(PAGE_SIZE));
        assertTrue(notifications.failures(PAGE_SIZE, 0).isEmpty());
        assertEquals(1, count("SELECT count(*) FROM public.team_notification_dispatches"));
        SecurityContextHolder.clearContext();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> systemDispatch(RETRY_DELAY));
            var second = workers.submit(() -> systemDispatch(RETRY_DELAY));
            assertEquals(0, first.get(CONCURRENT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals(0, second.get(CONCURRENT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        }
    }

    private UUID verifyAutomaticRecovery(UUID team, UUID leader) {
        installFailureTrigger();
        UUID membership;
        try {
            membership = operations.joinMember(team, leader, "Leader membership");
            SecurityContextHolder.clearContext();
            runApplicationProcess(null);
        } finally {
            removeFailureTrigger();
        }
        OffsetDateTime retry = pendingRetry();
        assertNotNull(retry);
        UUID source = fixtures.queryForObject("""
                SELECT COALESCE(operation_event_id,lifecycle_event_id) FROM public.team_notification_attempts
                WHERE outcome='FAILED' ORDER BY attempted_at DESC, attempt_no DESC LIMIT 1
                """, UUID.class);
        assertNotNull(source);
        assertEquals(retry, pendingRetry());
        pauseUntilDue();
        runApplicationProcess(source);
        assertEquals(1, count("""
                SELECT count(*) FROM public.team_notification_attempts
                WHERE outcome='DELIVERED' AND attempt_no=2 AND processor_kind='SYSTEM'
                """));
        authenticate(ADMIN_EMAIL);
        return membership;
    }

    private void runApplicationProcess(UUID recoverySource) {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var builder = new ProcessBuilder(javaExecutable, "-cp", classpath,
                TeamNotificationRestartProcess.class.getName());
        builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.INHERIT);
        var environment = builder.environment();
        environment.keySet().removeIf(key -> key.startsWith("EPMS_") || key.startsWith("SPRING_")
                || key.startsWith("FLYWAY_") || key.startsWith("PG") || key.equals("JAVA_TOOL_OPTIONS")
                || key.equals("JDK_JAVA_OPTIONS") || key.equals("_JAVA_OPTIONS"));
        environment.put("SPRING_DATASOURCE_URL", postgres.getJdbcUrl());
        environment.put("SPRING_DATASOURCE_USERNAME", postgres.getUsername());
        environment.put("SPRING_DATASOURCE_PASSWORD", postgres.getPassword());
        environment.put("EPMS_RBAC_JDBC_URL", postgres.getJdbcUrl());
        environment.put("EPMS_RBAC_USERNAME", "epms_rbac_runtime");
        environment.put("EPMS_RBAC_PASSWORD", "rbac-test-only-local-container-password");
        environment.put("EPMS_SECURITY_AUDIT_HMAC_KEY", "test-only-team-restart-audit-key-123456789");
        if (recoverySource != null) {
            environment.put("EPMS_TEST_RESTART_SOURCE", recoverySource.toString());
        }
        Process process = null;
        try {
            process = builder.start();
            assertTrue(process.waitFor(120, TimeUnit.SECONDS), "Separate EPMS application did not exit");
            assertEquals(0, process.exitValue(), "Separate EPMS application failed");
        } catch (java.io.IOException failure) {
            throw new AssertionError("Cannot start disposable EPMS process", failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Disposable EPMS process interrupted", failure);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private OffsetDateTime pendingRetry() {
        return fixtures.queryForObject("""
                SELECT next_retry_at FROM public.team_notification_attempts
                WHERE outcome='FAILED' ORDER BY attempted_at DESC, attempt_no DESC LIMIT 1
                """, OffsetDateTime.class);
    }

    private void verifyAccountDuringDispatch(UUID team, UUID leader, UUID member) throws Exception {
        authenticate(ADMIN_EMAIL);
        operations.transition(team, "ACTIVE", "Reactivate for account contention");
        notifications.dispatch(PAGE_SIZE);
        operations.joinMember(team, member, "Rejoin for account contention");
        int dispatches = count("SELECT count(*) FROM public.team_notification_dispatches");
        try (Connection blocker = Objects.requireNonNull(restricted.getDataSource()).getConnection();
                var workers = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try {
                try (PreparedStatement statement = blocker.prepareStatement(
                        "SELECT public.epms_team_system_dispatch(?, ?, ?)")) {
                    statement.setInt(1, MAX_ATTEMPTS);
                    statement.setInt(2, RETRY_DELAY);
                    statement.setInt(3, RETRY_DELAY);
                    try (var result = statement.executeQuery()) {
                        assertTrue(result.next());
                        assertEquals(1, result.getInt(1));
                    }
                }
                assertEquals(dispatches, count("SELECT count(*) FROM public.team_notification_dispatches"));
                var emergency = workers.submit(() -> {
                    authenticate(ADMIN_EMAIL);
                    try {
                        return accounts.disableAccount(leader, "ACCOUNT_SECURITY", SECRET_REASON);
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                });
                try {
                    assertNotNull(emergency.get(CONCURRENT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
                } catch (TimeoutException timeout) {
                    // Capture only wait metadata, not SQL text or connection credentials.
                    try {
                        timeout.addSuppressed(new IllegalStateException("PostgreSQL blockers: "
                                + fixtures.queryForList("""
                                        SELECT pid, wait_event_type, wait_event, pg_blocking_pids(pid) AS blockers
                                        FROM pg_stat_activity WHERE datname=current_database()
                                        AND cardinality(pg_blocking_pids(pid))>0 ORDER BY pid
                                        """)));
                    } catch (DataAccessException diagnosticFailure) {
                        timeout.addSuppressed(new IllegalStateException("Lock diagnostics unavailable"));
                    }
                    throw timeout;
                }
            } finally {
                blocker.rollback();
            }
        }
        assertFalse(Boolean.TRUE.equals(fixtures.queryForObject(
                "SELECT is_active FROM public.users WHERE user_id=?", Boolean.class, leader)));
        assertEquals("INACTIVE", profiles.get(team).lifecycleStatus());
        assertEquals(0, fixtures.queryForObject("""
                SELECT count(*) FROM public.security_user_roles WHERE user_id=? AND revoked_at IS NULL
                """, Integer.class, leader));
        assertEquals(dispatches, count("SELECT count(*) FROM public.team_notification_dispatches"));
        notifications.dispatch(PAGE_SIZE);
        assertTrue(count("SELECT count(*) FROM public.team_notification_dispatches") > dispatches);
    }

    private void verifyExhaustionAndRecovery(UUID team, UUID member) {
        UUID membership = fixtures.queryForObject("""
                SELECT team_membership_id FROM public.team_memberships
                WHERE team_id=? AND user_id=? AND left_at IS NULL
                """, UUID.class, team, member);
        installFailureTrigger();
        try {
            operations.leaveMember(team, membership, "ADMINISTRATIVE", "TEAM_REASSIGNMENT", "Remove member");
            SecurityContextHolder.clearContext();
            assertEquals(0, systemDispatch(SHORT_DELAY));
            pauseUntilDue();
            assertEquals(0, systemDispatch(SHORT_DELAY));
            authenticate(ADMIN_EMAIL);
            var failure = notifications.failures(PAGE_SIZE, 0).getFirst();
            assertEquals(MAX_ATTEMPTS, failure.attemptCount());
            assertTrue(failure.automaticExhausted());
        } finally {
            removeFailureTrigger();
        }
        SecurityContextHolder.clearContext();
        assertEquals(0, systemDispatch(SHORT_DELAY));
        authenticate(ADMIN_EMAIL);
        assertEquals(1, notifications.dispatch(PAGE_SIZE));
        assertTrue(notifications.failures(PAGE_SIZE, 0).isEmpty());
    }

    private void removeFailureTrigger() {
        fixtures.execute("DROP TRIGGER trg_test_notification_failure ON public.team_notifications");
        fixtures.execute("DROP FUNCTION public.epms_test_notification_failure()");
    }

    private void verifyEscalation(UUID team, UUID membership) throws Exception {
        TeamSecurityAlert alert = notifications.alerts(false, PAGE_SIZE, 0).getFirst();
        assertEquals(0, alert.escalationCount());
        SecurityContextHolder.clearContext();
        // Two competing workers may race, but every source is marked at most once.
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> systemDispatch(RETRY_DELAY));
            var second = workers.submit(() -> systemDispatch(RETRY_DELAY));
            assertTrue(first.get(CONCURRENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    + second.get(CONCURRENT_TIMEOUT_SECONDS, TimeUnit.SECONDS) >= 1);
        }
        automation.processPending();
        assertEquals(0, systemDispatch(RETRY_DELAY));
        assertTrue(count("""
                SELECT count(*) FROM public.team_notification_dispatches
                WHERE processor_kind='SYSTEM' AND processed_by_user_id IS NULL
                """) > 0);
        assertEquals(0, count("""
                SELECT count(*) FROM public.team_notification_attempts
                WHERE processor_kind='SYSTEM' AND actor_user_id IS NOT NULL
                """));
        pauseUntilDue();
        assertEquals(1, escalate());
        authenticate(ADMIN_EMAIL);
        TeamSecurityAlert escalated = notifications.alerts(false, PAGE_SIZE, 0).getFirst();
        assertEquals("UNACKNOWLEDGED", escalated.lastEscalationStage());
        assertEquals(1, escalated.escalationCount());
        notifications.acknowledgeAlert(alert.alertId());
        SecurityContextHolder.clearContext();
        pauseUntilDue();
        assertEquals(1, escalate());
        authenticate(ADMIN_EMAIL);
        assertEquals("UNRESOLVED", notifications.alerts(false, PAGE_SIZE, 0).getFirst().lastEscalationStage());
        operations.assignPermanent(team, membership, "Restore leadership");
        notifications.resolveAlert(alert.alertId(), "Resolved");
        SecurityContextHolder.clearContext();
        pauseUntilDue();
        assertEquals(0, escalate());
    }

    private int count(String query) {
        return Objects.requireNonNull(fixtures.queryForObject(query, Integer.class));
    }

    private int systemDispatch(int delay) {
        return Objects.requireNonNull(restricted.queryForObject(
                "SELECT public.epms_team_system_dispatch(?, ?, ?)", Integer.class, MAX_ATTEMPTS, delay, delay));
    }

    private int escalate() {
        return Objects.requireNonNull(restricted.queryForObject(
                "SELECT public.epms_team_system_escalate(?, ?, ?)", Integer.class,
                PAGE_SIZE, SHORT_DELAY, SHORT_DELAY));
    }

    private void pauseUntilDue() {
        fixtures.execute("SELECT pg_sleep(1.1)");
    }

    private void verifyRestrictedPrivileges() {
        assertThrows(DataAccessException.class,
                () -> restricted.queryForList("SELECT * FROM public.team_notification_attempts"));
        assertThrows(DataAccessException.class,
                () -> restricted.queryForList("SELECT * FROM public.team_security_alert_escalations"));
        assertThrows(DataAccessException.class,
                () -> fixtures.update("DELETE FROM public.team_notification_attempts"));
        assertThrows(DataAccessException.class,
                () -> fixtures.update("DELETE FROM public.team_security_alert_escalations"));
    }

    private UUID createUser(UUID org, String email, String role) {
        UUID account = fixtures.queryForObject("""
                INSERT INTO public.users(organization_id,account_type_id,first_name,last_name,email)
                SELECT ?,account_type_id,'Test','User',? FROM public.account_types WHERE code='HUMAN'
                RETURNING user_id
                """, UUID.class, org, email);
        fixtures.update("""
                INSERT INTO public.security_user_roles(user_id,role_id)
                SELECT ?,role_id FROM public.security_roles WHERE code=?
                """, account, role);
        return account;
    }

    private static void authenticate(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(email, null, List.of()));
    }
}
