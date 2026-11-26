package com.jamesstevens.epms.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jamesstevens.epms.common.exception.ResourceConflictException;
import com.jamesstevens.epms.team.dto.TeamNotification;
import com.jamesstevens.epms.team.dto.TeamProfileChange;
import com.jamesstevens.epms.team.dto.TeamSecurityAlert;
import com.jamesstevens.epms.team.service.TeamNotificationService;
import com.jamesstevens.epms.team.service.TeamOperationsService;
import com.jamesstevens.epms.team.service.TeamProfileService;
import java.util.List;
import java.util.UUID;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Committed Team events, actual protected commands, current recipient scope and independent red alerts. */
@SpringBootTest
@Testcontainers
class TeamNotificationIntegrationTest {
    private static final String ADMIN_EMAIL = "notification-admin@example.invalid";
    private static final String ORG_ADMIN_EMAIL = "notification-org-admin@example.invalid";
    private static final String LEADER_EMAIL = "notification-leader@example.invalid";
    private static final String MEMBER_EMAIL = "notification-member@example.invalid";
    private static final String OUTSIDER_EMAIL = "notification-outsider@example.invalid";
    private static final String SECRET_REASON = "Confidential emergency fixture reason";
    private static final int PAGE_SIZE = 100;

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
    @Autowired TeamNotificationService notifications;
    @Autowired JdbcTemplate fixtures;
    @Autowired @Qualifier("rbacJdbcTemplate") JdbcTemplate restricted;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void committedEventsCreateScopedDurableNoticesAndIndependentCriticalAlerts() {
        UUID org = fixtures.queryForObject("""
                INSERT INTO public.organizations(name) VALUES ('Notification integration') RETURNING organization_id
                """, UUID.class);
        createUser(org, ADMIN_EMAIL, "SYSTEM_ADMIN");
        createUser(org, ORG_ADMIN_EMAIL, "ORG_ADMIN");
        UUID leader = createUser(org, LEADER_EMAIL, "MEMBER");
        UUID member = createUser(org, MEMBER_EMAIL, "MEMBER");
        createUser(org, OUTSIDER_EMAIL, "MEMBER");
        assertThrows(AccessDeniedException.class, () -> notifications.inbox(PAGE_SIZE, 0));
        authenticate(ADMIN_EMAIL);
        UUID team = profiles.create(new TeamProfileChange("Notification Team", null, false, "Create"));
        UUID leaderMembership = operations.joinMember(team, leader, "Leader membership");
        UUID memberMembership = operations.joinMember(team, member, "Member appointment");
        UUID assignment = operations.assignPermanent(team, leaderMembership, "Appoint leader");
        operations.transition(team, "ACTIVE", "Activate");
        authenticate(LEADER_EMAIL);
        assertFalse(notifications.history(team, PAGE_SIZE, 0).isEmpty());
        authenticate(ADMIN_EMAIL);
        assertTrue(notifications.readiness(team).stream().allMatch(item -> "MISSING".equals(item.readiness())));
        operations.emergencyLeadership(team, assignment, "EMERGENCY_UNAVAILABILITY", SECRET_REASON);
        assertEquals("INACTIVE", profiles.get(team).lifecycleStatus());
        verifyConfidentialAccess(team);
        UUID alert = verifyCriticalAlertBeforeDispatch();
        verifyDispatchAndIsolation(team, alert);
        verifyRecipientReceiptsAndRevocation(team, memberMembership);
        verifyResolution(team, leaderMembership, alert);
    }

    private void verifyConfidentialAccess(UUID team) {
        UUID event = fixtures.queryForObject("""
                SELECT event_id FROM public.team_operation_events WHERE team_id=? AND operation='EMERGENCY'
                """, UUID.class, team);
        UUID operational = fixtures.queryForObject("""
                SELECT event_id FROM public.team_operation_events WHERE team_id=? AND operation='TEAM_CREATE'
                """, UUID.class, team);
        assertTrue(notifications.confidentialEvent(team, event, "OPERATION", "Review emergency")
                .contains(SECRET_REASON));
        assertThrows(IllegalArgumentException.class,
                () -> notifications.confidentialEvent(team, event, "OPERATION", " "));
        authenticate(ORG_ADMIN_EMAIL);
        assertTrue(notifications.confidentialEvent(team, operational, "OPERATION", "Review creation")
                .contains("TEAM_CREATE"));
        assertThrows(AccessDeniedException.class,
                () -> notifications.confidentialEvent(team, event, "OPERATION", "Review emergency"));
        authenticate(MEMBER_EMAIL);
        assertThrows(AccessDeniedException.class,
                () -> notifications.confidentialEvent(team, operational, "OPERATION", "Review creation"));
        authenticate(ADMIN_EMAIL);
        assertThrows(AccessDeniedException.class,
                () -> notifications.confidentialEvent(UUID.randomUUID(), event, "OPERATION", "Wrong scope"));
        verifyConfidentialAccessRecords();
        verifyAccessHistory(team);
    }

    private void verifyAccessHistory(UUID team) {
        var records = notifications.accessHistory(team, PAGE_SIZE, 0);
        assertEquals(4, records.size());
        assertEquals(2, records.stream().filter(row -> "GRANTED".equals(row.outcome())).count());
        assertEquals(2, records.stream().filter(row -> "DENIED".equals(row.outcome())).count());
        assertTrue(records.stream().allMatch(row -> team.equals(row.requestedTeamId())));
        assertEquals(1, notifications.accessHistory(team, 1, 0).size());
        assertEquals(records.get(1), notifications.accessHistory(team, 1, 1).getFirst());
        assertThrows(IllegalArgumentException.class, () -> notifications.accessHistory(team, 0, 0));
        assertThrows(AccessDeniedException.class, () -> notifications.accessHistory(UUID.randomUUID(), 1, 0));
        authenticate(ORG_ADMIN_EMAIL);
        assertThrows(AccessDeniedException.class, () -> notifications.accessHistory(team, PAGE_SIZE, 0));
        authenticate(MEMBER_EMAIL);
        assertThrows(AccessDeniedException.class, () -> notifications.accessHistory(team, PAGE_SIZE, 0));
        authenticate(ADMIN_EMAIL);
    }

    private void verifyConfidentialAccessRecords() {
        assertEquals(2, fixtures.queryForObject("""
                SELECT count(*) FROM public.team_confidential_audit_access WHERE outcome='GRANTED'
                """, Integer.class));
        assertEquals(3, fixtures.queryForObject("""
                SELECT count(*) FROM public.team_confidential_audit_access WHERE outcome='DENIED'
                """, Integer.class));
        assertThrows(DataAccessException.class,
                () -> restricted.queryForList("SELECT * FROM public.team_confidential_audit_access"));
        assertThrows(DataAccessException.class,
                () -> fixtures.update("DELETE FROM public.team_confidential_audit_access"));
    }

    private UUID verifyCriticalAlertBeforeDispatch() {
        assertTrue(notifications.inbox(PAGE_SIZE, 0).isEmpty());
        TeamSecurityAlert alert = notifications.alerts(false, PAGE_SIZE, 0).getFirst();
        assertEquals("NO_ELIGIBLE_SUCCESSOR", alert.alertCode());
        assertNull(alert.acknowledgedAt());
        assertNull(alert.resolvedAt());
        notifications.acknowledgeAlert(alert.alertId());
        notifications.acknowledgeAlert(alert.alertId());
        assertNotNull(notifications.alerts(false, PAGE_SIZE, 0).getFirst().acknowledgedAt());
        assertThrows(ResourceConflictException.class, () -> notifications.resolveAlert(alert.alertId(), "Unresolved"));
        return alert.alertId();
    }

    private void verifyDispatchAndIsolation(UUID team, UUID alert) {
        authenticate(MEMBER_EMAIL);
        assertThrows(AccessDeniedException.class, () -> notifications.dispatch(PAGE_SIZE));
        assertThrows(AccessDeniedException.class, () -> notifications.alerts(false, PAGE_SIZE, 0));
        assertThrows(AccessDeniedException.class, () -> notifications.acknowledgeAlert(alert));
        assertThrows(AccessDeniedException.class, () -> notifications.history(team, PAGE_SIZE, 0));
        assertThrows(AccessDeniedException.class, () -> notifications.readiness(team));
        authenticate(ADMIN_EMAIL);
        assertEquals("INACTIVE", profiles.get(team).lifecycleStatus());
        assertTrue(notifications.dispatch(PAGE_SIZE) > 0);
        assertEquals(0, notifications.dispatch(PAGE_SIZE));
        assertTrue(notifications.history(team, PAGE_SIZE, 0).stream()
                .anyMatch(event -> "EMERGENCY".equals(event.eventKind())));
        assertFalse(notifications.history(team, PAGE_SIZE, 0).toString().contains(SECRET_REASON));
        authenticate(ORG_ADMIN_EMAIL);
        TeamNotification critical = notifications.inbox(PAGE_SIZE, 0).stream()
                .filter(notice -> "CRITICAL".equals(notice.severity())).findFirst().orElseThrow();
        assertNull(notifications.alerts(false, PAGE_SIZE, 0).getFirst().acknowledgedAt());
        notifications.acknowledge(critical.notificationId());
        assertNotNull(notifications.alerts(false, PAGE_SIZE, 0).getFirst().acknowledgedAt());
        assertNull(notifications.alerts(false, PAGE_SIZE, 0).getFirst().resolvedAt());
        assertThrows(DataAccessException.class,
                () -> restricted.queryForList("SELECT * FROM public.team_notifications"));
        assertThrows(DataAccessException.class, () -> fixtures.update("DELETE FROM public.team_notifications"));
    }

    private void verifyRecipientReceiptsAndRevocation(UUID team, UUID membership) {
        authenticate(MEMBER_EMAIL);
        List<TeamNotification> inbox = notifications.inbox(PAGE_SIZE, 0);
        assertFalse(inbox.toString().contains(SECRET_REASON));
        assertTrue(inbox.stream().noneMatch(notice -> "CRITICAL".equals(notice.severity())));
        UUID notice = inbox.stream().filter(item -> "LEADERSHIP_CHANGED".equals(item.kind()))
                .findFirst().orElseThrow().notificationId();
        notifications.read(notice);
        assertNotNull(findNotice(notice).readAt());
        assertNull(findNotice(notice).acknowledgedAt());
        notifications.acknowledge(notice);
        notifications.acknowledge(notice);
        assertNotNull(findNotice(notice).acknowledgedAt());
        authenticate(OUTSIDER_EMAIL);
        assertTrue(notifications.inbox(PAGE_SIZE, 0).isEmpty());
        assertThrows(AccessDeniedException.class, () -> notifications.acknowledge(notice));
        authenticate(ADMIN_EMAIL);
        operations.leaveMember(team, membership, "ADMINISTRATIVE", "TEAM_REASSIGNMENT", "Remove membership");
        authenticate(MEMBER_EMAIL);
        assertTrue(notifications.inbox(PAGE_SIZE, 0).stream().noneMatch(item -> notice.equals(item.notificationId())));
        assertThrows(AccessDeniedException.class, () -> notifications.read(notice));
    }

    private TeamNotification findNotice(UUID notice) {
        return notifications.inbox(PAGE_SIZE, 0).stream().filter(item -> notice.equals(item.notificationId()))
                .findFirst().orElseThrow();
    }

    private void verifyResolution(UUID team, UUID membership, UUID alert) {
        authenticate(ADMIN_EMAIL);
        operations.assignPermanent(team, membership, "Restore eligible leadership");
        notifications.resolveAlert(alert, "Leadership restored");
        notifications.resolveAlert(alert, "Idempotent retry");
        assertTrue(notifications.alerts(false, PAGE_SIZE, 0).isEmpty());
        assertNotNull(notifications.alerts(true, PAGE_SIZE, 0).getFirst().resolvedAt());
        assertEquals(SECRET_REASON, fixtures.queryForObject("""
                SELECT reason FROM public.team_operation_events WHERE team_id=? AND operation='EMERGENCY'
                """, String.class, team));
        assertEquals("Leadership restored", fixtures.queryForObject("""
                SELECT reason FROM public.team_security_alert_resolutions WHERE alert_id=?
                """, String.class, alert));
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
