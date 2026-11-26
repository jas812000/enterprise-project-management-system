package com.jamesstevens.epms.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jamesstevens.epms.common.exception.ResourceConflictException;
import com.jamesstevens.epms.common.exception.ResourceNotFoundException;
import com.jamesstevens.epms.team.dto.TeamProfileChange;
import com.jamesstevens.epms.team.service.TeamAccountLifecycleService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Actual Spring services and restricted PostgreSQL commands on an isolated container. */
@SpringBootTest
@Testcontainers
class TeamSpringServiceIntegrationTest {
    private static final String ADMIN_EMAIL = "administrator@example.invalid";
    private static final String MEMBER_EMAIL = "member@example.invalid";
    private static final String ALTERNATE_EMAIL = "alternate@example.invalid";
    private static final String REPLACEMENT_EMAIL = "replacement@example.invalid";
    private static final String ORG_ADMIN_EMAIL = "org-admin@example.invalid";
    private static final String TEST_ONLY_PASSWORD = "rbac-test-only-local-container-password";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18")
            .withInitScript("rbac-test-db-init.sql");

    @DynamicPropertySource
    static void rbacProperties(DynamicPropertyRegistry registry) {
        registry.add("epms.rbac.enabled", () -> "true");
        registry.add("epms.rbac.jdbc-url", postgres::getJdbcUrl);
        registry.add("epms.rbac.username", () -> "epms_rbac_runtime");
        registry.add("epms.rbac.password", () -> TEST_ONLY_PASSWORD);
        registry.add("spring.flyway.enabled", () -> "true");

    }

    @Autowired
    TeamProfileService profiles;

    @Autowired
    TeamOperationsService operations;

    @Autowired
    TeamAccountLifecycleService lifecycle;

    @Autowired
    JdbcTemplate fixtures;

    @Autowired
    @Qualifier("rbacJdbcTemplate")
    JdbcTemplate restricted;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatedServicesUseProtectedCommandsAndRollbackRejectedProfileChanges() {
        assertNotNull(lifecycle);
        assertEquals("epms_rbac_runtime", restricted.queryForObject("SELECT current_user", String.class));
        UUID org = fixtures.queryForObject("""
                INSERT INTO public.organizations(name) VALUES ('Spring Team integration') RETURNING organization_id
                """, UUID.class);
        UUID admin = createUser(org, ADMIN_EMAIL);
        UUID member = createUser(org, MEMBER_EMAIL);
        fixtures.update("""
                INSERT INTO public.security_user_roles(user_id,role_id)
                SELECT ?,role_id FROM public.security_roles WHERE code='SYSTEM_ADMIN'
                """, admin);
        TeamProfileChange change = new TeamProfileChange("Platform", "Initial", false, "Create");
        assertThrows(AccessDeniedException.class, () -> profiles.create(change));
        authenticate(ADMIN_EMAIL);
        UUID team = profiles.create(change);
        assertNotNull(team);
        assertEquals("Platform", profiles.get(team).name());
        UUID membership = operations.joinMember(team, member, "Appoint member");
        assertNotNull(membership);
        long currentVersion = profiles.get(team).version();
        assertThrows(ResourceConflictException.class, () -> profiles.update(team, currentVersion + 1, change));
        assertEquals("Initial", profiles.get(team).description());
        assertEquals(currentVersion + 1, profiles.update(team, currentVersion,
                new TeamProfileChange("Platform", "Updated", false, "Modify")));
        assertThrows(ResourceConflictException.class, () -> operations.transition(team, "ACTIVE", "Activate"));
        assertEquals("PENDING_SETUP", profiles.get(team).lifecycleStatus());
        authenticate(MEMBER_EMAIL);
        assertThrows(AccessDeniedException.class, () -> profiles.get(team));
        assertThrows(AccessDeniedException.class, () -> operations.joinMember(team, admin, "Denied"));
        assertThrows(AccessDeniedException.class, () -> lifecycle.disableAccount(
                admin, "PLANNED_DEACTIVATION", "Denied"));
        assertThrows(AccessDeniedException.class, () -> profiles.create(change));
        verifyScopedReads(org, team, member, membership);
        verifyLeadershipLifecycle(org, team, membership);
    }

    private void verifyScopedReads(UUID org, UUID team, UUID member, UUID membership) {
        grantRole(member, "MEMBER");
        authenticate(ADMIN_EMAIL);
        UUID unrelated = profiles.create(new TeamProfileChange("Accounting", null, false, "Create"));
        authenticate(MEMBER_EMAIL);
        assertEquals(team, profiles.get(team).teamId());
        assertThrows(ResourceNotFoundException.class, () -> profiles.get(unrelated));
        assertEquals(List.of(team), profiles.list(1, 0).stream().map(profile -> profile.teamId()).toList());
        assertTrue(profiles.list(1, 1).isEmpty());
        assertThrows(AccessDeniedException.class, () -> profiles.reviewConflicts(null, "Platform"));
        assertThrows(AccessDeniedException.class, () -> operations.joinMember(team, member, "Denied"));
        verifyDeferredScopes(org, team);
        verifyExpiredRole(org, team);
        authenticate(ADMIN_EMAIL);
        assertThrows(IllegalArgumentException.class, () -> operations.leaveMember(
                team, membership, "ADMINISTRATIVE", "INVALID", "Invalid reason code"));
        authenticate(MEMBER_EMAIL);
        assertEquals(team, profiles.get(team).teamId());
    }

    private void verifyDeferredScopes(UUID org, UUID team) {
        for (String role : List.of("PROJECT_MANAGER", "VIEWER", "AUDITOR")) {
            String email = role + "@example.invalid";
            UUID candidate = createUser(org, email);
            grantRole(candidate, role);
            authenticate(email);
            assertTrue(profiles.list(10, 0).isEmpty());
            assertThrows(ResourceNotFoundException.class, () -> profiles.get(team));
        }
        UUID orgAdmin = createUser(org, ORG_ADMIN_EMAIL);
        grantRole(orgAdmin, "ORG_ADMIN");
        authenticate(ORG_ADMIN_EMAIL);
        assertEquals(team, profiles.get(team).teamId());
    }

    private void verifyExpiredRole(UUID org, UUID team) {
        authenticate(ADMIN_EMAIL);
        String email = "expired@example.invalid";
        UUID candidate = createUser(org, email);
        operations.joinMember(team, candidate, "Expired role fixture");
        fixtures.update("""
                INSERT INTO public.security_user_roles(user_id,role_id,valid_from,valid_until)
                SELECT ?,role_id,clock_timestamp()-interval '2 days',clock_timestamp()-interval '1 day'
                FROM public.security_roles WHERE code='MEMBER'
                """, candidate);
        authenticate(email);
        assertThrows(AccessDeniedException.class, () -> profiles.get(team));
    }

    private void verifyLeadershipLifecycle(UUID org, UUID team, UUID membership) {
        authenticate(ADMIN_EMAIL);
        UUID alternateUser = createUser(org, ALTERNATE_EMAIL);
        UUID replacementUser = createUser(org, REPLACEMENT_EMAIL);
        UUID alternateMember = operations.joinMember(team, alternateUser, "Alternate member");
        UUID replacementMember = operations.joinMember(team, replacementUser, "Replacement member");
        UUID firstLeader = operations.assignPermanent(team, membership, "Appoint leader");
        UUID alternate = operations.designateAlternate(team, alternateMember, (short) 1, "Designate");
        operations.revokeAlternate(team, alternate, "Revoke designation");
        operations.designateAlternate(team, alternateMember, (short) 1, "Designate again");
        operations.transition(team, "ACTIVE", "Activate");
        authenticate(MEMBER_EMAIL);
        assertTrue(operations.hasPermission(team, "TASK_ASSIGN"));
        authenticate(ALTERNATE_EMAIL);
        assertFalse(operations.hasPermission(team, "TASK_ASSIGN"));
        authenticate(ADMIN_EMAIL);
        assertThrows(ResourceConflictException.class,
                () -> operations.endPlanned(team, firstLeader, null, "No replacement"));
        operations.endPlanned(team, firstLeader, replacementMember, "Planned replacement");
        operations.leaveMember(team, membership, "ADMINISTRATIVE", "TEAM_REASSIGNMENT", "Remove member");
        authenticate(MEMBER_EMAIL);
        assertThrows(ResourceNotFoundException.class, () -> profiles.get(team));
        authenticate(REPLACEMENT_EMAIL);
        assertEquals(team, profiles.get(team).teamId());
        assertTrue(operations.hasPermission(team, "TASK_ASSIGN"));
        verifyEmergencyAndAccountLifecycle(team, replacementUser, alternateUser);
    }

    private void verifyEmergencyAndAccountLifecycle(UUID team, UUID replacementUser, UUID alternateUser) {
        authenticate(ADMIN_EMAIL);
        UUID replacement = fixtures.queryForObject("""
                SELECT team_leadership_assignment_id FROM public.team_leadership_assignments
                WHERE team_id=? AND user_id=? AND ended_at IS NULL
                """, UUID.class, team, replacementUser);
        assertNotNull(operations.emergencyLeadership(team, replacement,
                "EMERGENCY_UNAVAILABILITY", "Emergency"));
        authenticate(ALTERNATE_EMAIL);
        assertEquals(team, profiles.get(team).teamId());
        assertTrue(operations.hasPermission(team, "TASK_ASSIGN"));
        authenticate(ADMIN_EMAIL);
        lifecycle.disableAccount(alternateUser, "EMERGENCY_DEACTIVATION", "Security emergency");
        assertEquals("INACTIVE", profiles.get(team).lifecycleStatus());
        lifecycle.disableAccount(replacementUser, "PLANNED_ARCHIVAL", "Archive former leader");
        assertEquals(Boolean.TRUE, fixtures.queryForObject(
                "SELECT is_archived FROM public.users WHERE user_id=?", Boolean.class, replacementUser));
        authenticate(ALTERNATE_EMAIL);
        assertThrows(AccessDeniedException.class, () -> profiles.get(team));
    }

    private void grantRole(UUID user, String role) {
        fixtures.update("""
                INSERT INTO public.security_user_roles(user_id,role_id)
                SELECT ?,role_id FROM public.security_roles WHERE code=?
                """, user, role);
    }

    private UUID createUser(UUID org, String email) {
        return fixtures.queryForObject("""
                INSERT INTO public.users(organization_id,account_type_id,first_name,last_name,email)
                SELECT ?,account_type_id,'Test','User',? FROM public.account_types WHERE code='HUMAN'
                RETURNING user_id
                """, UUID.class, org, email);
    }

    private static void authenticate(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(email, null, List.of()));
    }
}
