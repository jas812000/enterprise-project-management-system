package com.jamesstevens.epms.security.rbac;

import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Tests real JDBC connections, not a mocked RBAC service. */
@SpringBootTest
@Testcontainers
class RbacRuntimeDatasourceIntegrationTest {
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
    @Qualifier("rbacJdbcTemplate")
    JdbcTemplate restricted;

    @Autowired
    @Qualifier("dataSource")
    javax.sql.DataSource applicationDataSource;

    @Autowired
    JdbcTemplate defaultTemplate;

    @Test
    void defaultTemplateUsesApplicationDatasource() {
        assertSame(applicationDataSource, defaultTemplate.getDataSource());
        assertNotSame(restricted, defaultTemplate);
        assertNotEquals("epms_rbac_runtime", defaultTemplate.queryForObject("SELECT current_user", String.class));
        assertEquals(application().queryForObject("SELECT current_user", String.class),
                defaultTemplate.queryForObject("SELECT current_user", String.class));
    }

    private JdbcTemplate application() {
        return new JdbcTemplate(applicationDataSource);
    }

    @Test
    void restrictedConnectionUsesDedicatedIdentityAndDifferentPool() {
        assertEquals("epms_rbac_runtime", restricted.queryForObject("SELECT current_user", String.class));
        assertNotEquals("epms_rbac_runtime", application().queryForObject("SELECT current_user", String.class));
        assertNotSame(applicationDataSource, restricted.getDataSource());
        assertTrue(restricted.getDataSource() instanceof HikariDataSource);
        var pool = (HikariDataSource) restricted.getDataSource();
        assertEquals("epms-rbac-restricted", pool.getPoolName());
        assertEquals(3, pool.getMaximumPoolSize());
    }

    @Test
    void ordinaryApplicationRoleCannotInvokePrivilegedRbacFunctions() {
        Map<String, Object> privileges = application().queryForMap("""
                SELECT has_function_privilege('epms_app',
                           'public.epms_rbac_assign_role(uuid,uuid,text,text)', 'EXECUTE') AS app_assign,
                       has_function_privilege('epms_app',
                           'public.epms_rbac_revoke_role(uuid,uuid,text)', 'EXECUTE') AS app_revoke,
                       has_table_privilege('epms_app',
                           'public.security_user_roles', 'INSERT,UPDATE,DELETE') AS app_role_write,
                       has_table_privilege('epms_app',
                           'public.security_role_assignment_history', 'INSERT,UPDATE,DELETE') AS app_audit_write,
                       has_function_privilege('epms_rbac_runtime',
                           'public.epms_rbac_assign_role(uuid,uuid,text,text)', 'EXECUTE') AS restricted_assign,
                       has_function_privilege('epms_rbac_runtime',
                           'public.epms_rbac_revoke_role(uuid,uuid,text)', 'EXECUTE') AS restricted_revoke
                """);
        assertEquals(false, privileges.get("app_assign"));
        assertEquals(false, privileges.get("app_revoke"));
        assertEquals(false, privileges.get("app_role_write"));
        assertEquals(false, privileges.get("app_audit_write"));
        assertEquals(true, privileges.get("restricted_assign"));
        assertEquals(true, privileges.get("restricted_revoke"));
    }

    @Test
    void runtimeRoleDoesNotInheritPrivilegedOwnerAndHasNoSchemaCreate() {
        Map<String, Object> privileges = restricted.queryForMap("""
                SELECT (SELECT rolinherit FROM pg_roles WHERE rolname = current_user) AS inherits_roles,
                       (SELECT rolbypassrls FROM pg_roles WHERE rolname = current_user) AS bypasses_rls,
                       has_schema_privilege(current_user, 'public', 'CREATE') AS schema_create,
                       has_table_privilege(current_user,
                           'public.security_role_assignment_history', 'UPDATE,DELETE') AS audit_mutation
                """);
        assertEquals(false, privileges.get("inherits_roles"));
        assertEquals(false, privileges.get("bypasses_rls"));
        assertEquals(false, privileges.get("schema_create"));
        assertEquals(false, privileges.get("audit_mutation"));
    }

    @Test
    void restrictedConnectionCanExecuteButCannotWriteRbacTablesDirectly() {
        Map<String, Object> privileges = restricted.queryForMap("""
                SELECT has_function_privilege(current_user,
                    'public.epms_rbac_assign_role(uuid,uuid,text,text)', 'EXECUTE') AS can_assign,
                    has_function_privilege(current_user,
                    'public.epms_rbac_revoke_role(uuid,uuid,text)', 'EXECUTE') AS can_revoke,
                    has_table_privilege(current_user, 'public.security_user_roles', 'INSERT') AS can_insert
                """);
        assertEquals(true, privileges.get("can_assign"));
        assertEquals(true, privileges.get("can_revoke"));
        assertEquals(false, privileges.get("can_insert"));
    }
}
