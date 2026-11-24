package com.jamesstevens.epms.security.rbac;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Executes all versioned migrations on isolated PostgreSQL 18, including V14. */
@Testcontainers
class BootstrapV14MigrationIntegrationTest {
    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18")
            .withInitScript("rbac-test-db-init.sql");

    @Test
    void migrationAndBootstrapSafeguards() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement()) {
            assertTrue(booleanQuery(statement, "SELECT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version = '14' AND success)"));
            assertTrue(booleanQuery(statement, "SELECT EXISTS (SELECT 1 FROM public.security_bootstrap_state WHERE singleton_id = 1 AND completed_at IS NULL AND administrator_user_id IS NULL)"));
            assertFalse(booleanQuery(statement, "SELECT has_table_privilege('epms_app', 'public.security_bootstrap_state', 'INSERT,UPDATE,DELETE')"));
            assertFalse(booleanQuery(statement, "SELECT has_table_privilege('epms_rbac_runtime', 'public.security_bootstrap_state', 'INSERT,UPDATE,DELETE')"));
            assertFalse(booleanQuery(statement, "SELECT has_table_privilege('epms_app', 'public.security_bootstrap_audit', 'INSERT,UPDATE,DELETE')"));
            assertFalse(booleanQuery(statement, "SELECT has_table_privilege('epms_rbac_runtime', 'public.security_bootstrap_audit', 'INSERT,UPDATE,DELETE')"));
            assertFalse(booleanQuery(statement, "SELECT has_function_privilege('epms_app', 'public.epms_bootstrap_state_guard()', 'EXECUTE')"));
            assertFalse(booleanQuery(statement, "SELECT has_function_privilege('epms_rbac_runtime', 'public.epms_bootstrap_state_guard()', 'EXECUTE')"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("DELETE FROM public.security_bootstrap_state WHERE singleton_id = 1"));
            assertTrue(booleanQuery(statement, "SELECT EXISTS (SELECT 1 FROM public.security_bootstrap_state WHERE singleton_id = 1)"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE public.security_bootstrap_state SET completed_at = clock_timestamp() WHERE singleton_id = 1"));
            assertTrue(booleanQuery(statement, "SELECT EXISTS (SELECT 1 FROM public.security_bootstrap_state WHERE singleton_id = 1 AND completed_at IS NULL)"));
        }
    }

    private static boolean booleanQuery(Statement statement, String sql) throws SQLException {
        try (ResultSet rs = statement.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getBoolean(1);
        }
    }
}
