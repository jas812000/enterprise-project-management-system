package com.jamesstevens.epms.security.rbac;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicitly opt-in, read-only local DB check. Never run in normal CI. */
@EnabledIfSystemProperty(named = "epms.live.audit", matches = "true")
class LiveDatasourceIdentityOptInTest {
    @Test
    void actualSpringManagedConnectionsUseLeastPrivilegeRoles() {
        String appUser = required("EPMS_DB_USERNAME");
        String appPassword = required("EPMS_DB_PASSWORD");
        String rbacUser = required("EPMS_RBAC_USERNAME");
        String rbacPassword = required("EPMS_RBAC_PASSWORD");
        String appUrl = System.getenv().getOrDefault(
                "EPMS_DB_URL",
                "jdbc:" + "postgresql://localhost:5432/epms");
        String rbacUrl = System.getenv().getOrDefault("EPMS_RBAC_JDBC_URL", appUrl);
        assertEquals("epms_app", appUser);
        assertEquals("epms_rbac_runtime", rbacUser);
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
            .withUserConfiguration(RbacJdbcConfig.class)
            .withPropertyValues(
                "spring.datasource.url=" + appUrl,
                "spring.datasource.username=" + appUser,
                "spring.datasource.password=" + appPassword,
                "epms.rbac.enabled=true",
                "epms.rbac.jdbc-url=" + rbacUrl,
                "epms.rbac.username=" + rbacUser,
                "epms.rbac.password=" + rbacPassword)
            .run(context -> {
                assertNull(context.getStartupFailure(), "Spring datasource context must start");
                var app = new JdbcTemplate(context.getBean(javax.sql.DataSource.class));
                var restricted = context.getBean("rbacJdbcTemplate", JdbcTemplate.class);
                assertNotSame(app.getDataSource(), restricted.getDataSource());
                verifyIdentity(app, "epms_app");
                verifyIdentity(restricted, "epms_rbac_runtime");
                assertEquals(false, app.queryForObject(
                        "SELECT has_function_privilege(current_user, " +
                                "'public.epms_rbac_assign_role(uuid,uuid,text,text)', 'EXECUTE')", Boolean.class));
                assertEquals(true, restricted.queryForObject(
                        "SELECT has_function_privilege(current_user, " +
                                "'public.epms_rbac_assign_role(uuid,uuid,text,text)', 'EXECUTE')", Boolean.class));
                assertEquals(false, restricted.queryForObject(
                        "SELECT has_table_privilege(current_user, " +
                                "'public.security_user_roles', 'INSERT,UPDATE,DELETE')", Boolean.class));
            });
    }
    private static void verifyIdentity(JdbcTemplate jdbc, String expected) {
        Map<String,Object> result = jdbc.queryForMap("""
            SELECT current_user AS effective_role, session_user AS login_role,
              has_schema_privilege(current_user, 'public', 'CREATE') AS can_create,
              (SELECT rolbypassrls FROM pg_roles WHERE rolname=current_user) AS bypasses_rls
            """);
        assertEquals(expected, result.get("effective_role"));
        assertEquals(expected, result.get("login_role"));
        assertEquals(false, result.get("can_create"));
        assertEquals(false, result.get("bypasses_rls"));
    }
    private static String required(String key) {
        String value = System.getenv(key);
        assertNotNull(value, "Missing required environment variable: " + key);
        assertFalse(value.isBlank(), "Empty required environment variable: " + key);
        return value;
    }
}
