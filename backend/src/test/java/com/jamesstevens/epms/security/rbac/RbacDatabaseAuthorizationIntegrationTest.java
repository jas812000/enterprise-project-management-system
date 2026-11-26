package com.jamesstevens.epms.security.rbac;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
class RbacDatabaseAuthorizationIntegrationTest {
    private static final String PASSWORD = "rbac-test-only-local-container-password";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18")
            .withInitScript("rbac-test-db-init.sql");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("epms.rbac.enabled", () -> "true");
        registry.add("epms.rbac.jdbc-url", postgres::getJdbcUrl);
        registry.add("epms.rbac.username", () -> "epms_rbac_runtime");
        registry.add("epms.rbac.password", () -> PASSWORD);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    @Qualifier("rbacJdbcTemplate")
    JdbcTemplate restricted;

    @Autowired
    @Qualifier("dataSource")
    javax.sql.DataSource applicationDataSource;

    JdbcTemplate setup;
    UUID systemAdmin;
    UUID orgAdmin;
    UUID ordinary;
    UUID target;

    @BeforeEach
    void prepareIsolatedActors() {
        setup = new JdbcTemplate(applicationDataSource);
        var existingOrganizations = setup.queryForList(
                "SELECT organization_id FROM public.organizations LIMIT 1", UUID.class);
        UUID org = existingOrganizations.isEmpty() ? null : existingOrganizations.getFirst();
        if (org == null) {
            org = setup.queryForObject(
                    "INSERT INTO public.organizations(name) VALUES (?) RETURNING organization_id",
                    UUID.class, "RBAC test organization");
        }
        assertNotNull(org);
        systemAdmin = createUser(org, "sys");
        orgAdmin = createUser(org, "org");
        ordinary = createUser(org, "ordinary");
        target = createUser(org, "target");
        seedRole(systemAdmin, "SYSTEM_ADMIN");
        seedRole(orgAdmin, "ORG_ADMIN");
        seedRole(ordinary, "MEMBER");
    }

    private UUID createUser(UUID organization, String prefix) {
        return setup.queryForObject("""
                INSERT INTO public.users(organization_id, account_type_id, first_name, last_name, email)
                SELECT ?, account_type_id, ?, 'RBAC', ? FROM public.account_types WHERE code='HUMAN'
                RETURNING user_id
                """, UUID.class, organization, prefix, prefix + UUID.randomUUID() + "@example.invalid");
    }

    private void seedRole(UUID user, String code) {
        setup.update("""
                INSERT INTO public.security_user_roles(user_id, role_id, granted_by_user_id)
                SELECT ?, role_id, ? FROM public.security_roles WHERE code=?
                """, user, systemAdmin, code);
    }

    private UUID assign(UUID actor, UUID recipient, String role) {
        return restricted.queryForObject("SELECT public.epms_rbac_assign_role(?,?,?,?)",
                UUID.class, actor, recipient, role, "integration acceptance");
    }

    private UUID revoke(UUID actor, UUID assignment) {
        return restricted.queryForObject("SELECT public.epms_rbac_revoke_role(?,?,?)",
                UUID.class, actor, assignment, "integration revocation");
    }

    @Test
    void systemAdminCanAssignAndRevokeWithImmutableAuditEvidence() {
        UUID assignment = assign(systemAdmin, target, "PROJECT_MANAGER");
        assertNotNull(assignment);
        assertEquals(1, setup.queryForObject("""
                SELECT count(*) FROM public.security_user_roles
                WHERE assignment_id=? AND revoked_at IS NULL
                """, Integer.class, assignment));
        assertEquals(assignment, revoke(systemAdmin, assignment));
        assertEquals(1, setup.queryForObject("""
                SELECT count(*) FROM public.security_user_roles
                WHERE assignment_id=? AND revoked_at IS NOT NULL
                """, Integer.class, assignment));
        assertEquals(2, setup.queryForObject("""
                SELECT count(*) FROM public.security_role_assignment_history
                WHERE assignment_id=? AND actor_user_id=? AND action IN ('GRANT','REVOKE')
                """, Integer.class, assignment, systemAdmin));
        assertThrows(DataAccessException.class, () -> restricted.update("""
                UPDATE public.security_role_assignment_history SET reason='tampered'
                WHERE assignment_id=?
                """, assignment));
    }

    @Test
    void orgAdminCanAssignOperationalRoleButCannotAssignAuditor() {
        assertNotNull(assign(orgAdmin, target, "PROJECT_MANAGER"));
        assertThrows(DataAccessException.class, () -> assign(orgAdmin, target, "TEAM_LEAD"));
        assertThrows(DataAccessException.class, () -> assign(orgAdmin, target, "AUDITOR"));
    }

    @Test
    void ordinaryUserCannotAssignRolesAndSelfAssignmentIsRejected() {
        assertThrows(DataAccessException.class, () -> assign(ordinary, target, "VIEWER"));
        assertThrows(DataAccessException.class, () -> assign(systemAdmin, systemAdmin, "VIEWER"));
        assertEquals(0, setup.queryForObject("""
                SELECT count(*) FROM public.security_role_assignment_history
                WHERE target_user_id=?
                """, Integer.class, target));
    }
    @Test
    void restrictedIdentityCannotMutateAuditOrRolesDirectly() {
        UUID assignment = assign(systemAdmin, target, "VIEWER");
        assertThrows(DataAccessException.class, () -> restricted.update(
                "DELETE FROM public.security_role_assignment_history WHERE assignment_id=?", assignment));
        assertThrows(DataAccessException.class, () -> restricted.update(
                "UPDATE public.security_user_roles SET revoked_at=clock_timestamp() WHERE assignment_id=?", assignment));
        assertEquals(1, setup.queryForObject(
                "SELECT count(*) FROM public.security_role_assignment_history WHERE assignment_id=? AND action='GRANT'",
                Integer.class, assignment));
    }

    @Test
    void failedRevocationAndInvalidAssignmentLeaveNoAuditRows() {
        UUID assignment = assign(systemAdmin, target, "VIEWER");
        assertThrows(DataAccessException.class, () -> revoke(ordinary, assignment));
        assertThrows(DataAccessException.class, () -> assign(orgAdmin, target, "AUDITOR"));
        assertEquals(1, setup.queryForObject(
                "SELECT count(*) FROM public.security_role_assignment_history WHERE assignment_id=?",
                Integer.class, assignment));
        assertEquals(1, setup.queryForObject(
                "SELECT count(*) FROM public.security_user_roles WHERE assignment_id=? AND revoked_at IS NULL",
                Integer.class, assignment));
        assertEquals(0, setup.queryForObject(
                "SELECT count(*) FROM public.security_role_assignment_history WHERE target_user_id=? AND action='REVOKE'",
                Integer.class, target));
    }

    @Test
    void auditorCannotCoexistWithOtherRolesAndOrgAdminCannotRevokeAuditor() {
        UUID auditorTarget = createUser(setup.queryForObject(
                "SELECT organization_id FROM public.users WHERE user_id=?", UUID.class, target), "auditor");
        UUID auditorAssignment = assign(systemAdmin, auditorTarget, "AUDITOR");
        assertThrows(DataAccessException.class, () -> assign(systemAdmin, auditorTarget, "VIEWER"));
        assertThrows(DataAccessException.class, () -> revoke(orgAdmin, auditorAssignment));
        assertEquals(1, setup.queryForObject(
                "SELECT count(*) FROM public.security_user_roles WHERE assignment_id=? AND revoked_at IS NULL",
                Integer.class, auditorAssignment));
    }

    /** Authorization is re-evaluated by the database for each operation, not cached by actor. */
    @Test
    void revokedAdministratorCannotPerformNextOperationWithSameActorIdentity() {
        UUID delegate = createUser(setup.queryForObject(
                "SELECT organization_id FROM public.users WHERE user_id=?", UUID.class, target), "delegate");
        UUID adminAssignment = assign(systemAdmin, delegate, "ORG_ADMIN");
        UUID first = assign(delegate, target, "VIEWER");
        assertNotNull(first);
        assertEquals(adminAssignment, revoke(systemAdmin, adminAssignment));
        UUID secondTarget = createUser(setup.queryForObject(
                "SELECT organization_id FROM public.users WHERE user_id=?", UUID.class, target), "second");
        assertThrows(DataAccessException.class, () -> assign(delegate, secondTarget, "VIEWER"));
        assertEquals(0, setup.queryForObject("""
                SELECT count(*) FROM public.security_user_roles WHERE user_id=?
                """, Integer.class, secondTarget));
        assertEquals(0, setup.queryForObject("""
                SELECT count(*) FROM public.security_role_assignment_history WHERE target_user_id=?
                """, Integer.class, secondTarget));
    }

    @Test
    void concurrentDuplicateAssignmentsProduceOneGrant() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Boolean> action = () -> {
                ready.countDown();
                if (!go.await(15, java.util.concurrent.TimeUnit.SECONDS))
                    throw new IllegalStateException("Concurrent start timed out");
                try {
                    assign(systemAdmin, target, "VIEWER");
                    return true;
                } catch (DataAccessException expectedDuplicate) {
                    return false;
                }
            };
            java.util.concurrent.Future<Boolean> first = pool.submit(action);
            java.util.concurrent.Future<Boolean> second = pool.submit(action);
            assertTrue(ready.await(15, java.util.concurrent.TimeUnit.SECONDS));
            go.countDown();
            int successes = (first.get(30, java.util.concurrent.TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(30, java.util.concurrent.TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, successes);
            assertEquals(1, setup.queryForObject("""
                    SELECT count(*) FROM public.security_user_roles a
                    JOIN public.security_roles r ON r.role_id=a.role_id
                    WHERE a.user_id=? AND r.code='VIEWER' AND a.revoked_at IS NULL
                    """, Integer.class, target));
            assertEquals(1, setup.queryForObject("""
                    SELECT count(*) FROM public.security_role_assignment_history h
                    WHERE h.target_user_id=? AND h.action='GRANT'
                    """, Integer.class, target));
        } finally {
            go.countDown();
            pool.shutdownNow();
        }
    }

}
