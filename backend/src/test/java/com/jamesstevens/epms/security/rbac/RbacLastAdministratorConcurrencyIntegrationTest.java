package com.jamesstevens.epms.security.rbac;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.concurrent.*;
import javax.sql.DataSource;
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
class RbacLastAdministratorConcurrencyIntegrationTest {
    @Container @ServiceConnection
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

    @Autowired @Qualifier("rbacJdbcTemplate") JdbcTemplate restricted;
    @Autowired @Qualifier("dataSource") DataSource applicationDataSource;

    private UUID user(JdbcTemplate setup, UUID org) {
        return setup.queryForObject("""
                INSERT INTO public.users(organization_id,account_type_id,first_name,last_name,email)
                SELECT ?,account_type_id,'Admin','Concurrency',? FROM public.account_types WHERE code='HUMAN'
                RETURNING user_id
                """, UUID.class, org, "admin-" + UUID.randomUUID() + "@example.invalid");
    }

    private UUID grant(JdbcTemplate setup, UUID target, UUID actor) {
        return setup.queryForObject("""
                INSERT INTO public.security_user_roles(user_id,role_id,granted_by_user_id)
                SELECT ?,role_id,? FROM public.security_roles WHERE code='SYSTEM_ADMIN'
                RETURNING assignment_id
                """, UUID.class, target, actor);
    }

    @Test
    void competingAdministratorRevocationsCannotRemoveEveryAdministrator() throws Exception {
        JdbcTemplate setup = new JdbcTemplate(applicationDataSource);
        UUID org = setup.queryForObject(
                "INSERT INTO public.organizations(name) VALUES (?) RETURNING organization_id",
                UUID.class, "Last admin concurrency " + UUID.randomUUID());
        UUID first = user(setup, org);
        UUID second = user(setup, org);
        UUID firstAssignment = grant(setup, first, first);
        UUID secondAssignment = grant(setup, second, first);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Callable<Boolean> revokeFirst = () -> revoke(ready, go, second, firstAssignment);
            Callable<Boolean> revokeSecond = () -> revoke(ready, go, first, secondAssignment);
            Future<Boolean> a = pool.submit(revokeFirst);
            Future<Boolean> b = pool.submit(revokeSecond);
            assertTrue(ready.await(15, TimeUnit.SECONDS));
            go.countDown();
            int successes = (a.get(30, TimeUnit.SECONDS) ? 1 : 0)
                    + (b.get(30, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, successes, "Exactly one administrator revocation should succeed");
            assertEquals(1, setup.queryForObject("""
                    SELECT count(*) FROM public.security_user_roles a
                    JOIN public.security_roles r ON r.role_id=a.role_id
                    JOIN public.users u ON u.user_id=a.user_id
                    WHERE r.code='SYSTEM_ADMIN' AND a.revoked_at IS NULL
                      AND u.is_active AND NOT u.is_archived
                    """, Integer.class));
            UUID survivor = setup.queryForObject("""
                    SELECT a.user_id FROM public.security_user_roles a
                    JOIN public.security_roles r ON r.role_id=a.role_id
                    WHERE r.code='SYSTEM_ADMIN' AND a.revoked_at IS NULL
                    """, UUID.class);
            assertThrows(DataAccessException.class, () -> setup.update(
                    "UPDATE public.users SET is_active=false WHERE user_id=?", survivor));
            assertThrows(DataAccessException.class, () -> setup.update(
                    "UPDATE public.users SET is_archived=true WHERE user_id=?", survivor));
            assertEquals(1, setup.queryForObject("""
                    SELECT count(*) FROM public.security_role_assignment_history
                    WHERE action='REVOKE' AND assignment_id IN (?,?)
                    """, Integer.class, firstAssignment, secondAssignment));
        } finally {
            go.countDown();
            pool.shutdownNow();
        }
    }

    private boolean revoke(CountDownLatch ready, CountDownLatch go, UUID actor, UUID assignment)
            throws InterruptedException {
        ready.countDown();
        if (!go.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
        try {
            restricted.queryForObject("SELECT public.epms_rbac_revoke_role(?,?,?)",
                    UUID.class, actor, assignment, "concurrent last administrator test");
            return true;
        } catch (DataAccessException rejected) {
            return false;
        }
    }
}
