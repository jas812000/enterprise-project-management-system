package com.jamesstevens.epms.security.bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Isolated PostgreSQL 18 bootstrap privilege, atomicity, and replay acceptance. */
@Testcontainers
class BootstrapV15AuthorityIntegrationTest {
    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18")
            .withInitScript("rbac-test-db-init.sql");

    @Test
    void restrictedBootstrapIsAtomicAndSingleUse() throws Exception {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword()).locations("classpath:db/migration").load().migrate();
        try (Connection admin = DriverManager.getConnection(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword());
             Statement statement = admin.createStatement()) {
            statement.executeUpdate("INSERT INTO public.organizations(name) VALUES ('Bootstrap Test')");
            assertFalse(bool(statement, "SELECT has_function_privilege('epms_app',"
                    + " 'public.epms_bootstrap_initial_admin(text,text,text,text)', 'EXECUTE')"));
            assertFalse(bool(statement, "SELECT has_function_privilege('epms_rbac_runtime',"
                    + " 'public.epms_bootstrap_initial_admin(text,text,text,text)', 'EXECUTE')"));
            assertTrue(bool(statement, "SELECT has_function_privilege('epms_bootstrap_runtime',"
                    + " 'public.epms_bootstrap_initial_admin(text,text,text,text)', 'EXECUTE')"));
            assertFalse(bool(statement, "SELECT has_table_privilege('epms_bootstrap_runtime',"
                    + " 'public.security_user_roles', 'INSERT')"));
        }
        String url = postgres.getJdbcUrl();
        try (Connection runtime = DriverManager.getConnection(url,
                "epms_bootstrap_runtime", "bootstrap-test-only-local-container-password")) {
            assertThrows(SQLException.class, () -> bootstrap(runtime, "not-a-hash"));
            UUID adminId = bootstrap(runtime, "$argon2id$v=19$m=19456,t=2,p=1$c29tZXNhbHRzYWx0$ZmFrZWhhc2hmb3JkYnRlc3Q");
            assertNotNull(adminId);
            assertThrows(SQLException.class, () -> bootstrap(runtime,
                    "$argon2id$v=19$m=19456,t=2,p=1$c29tZXNhbHRzYWx0$ZmFrZWhhc2hmb3JkYnRlc3Q"));
        }
        try (Connection admin = DriverManager.getConnection(url,
                postgres.getUsername(), postgres.getPassword());
             Statement statement = admin.createStatement()) {
            assertTrue(bool(statement, "SELECT completed_at IS NOT NULL FROM public.security_bootstrap_state"));
            assertTrue(bool(statement, "SELECT count(*)=1 FROM public.security_bootstrap_audit"));
            assertTrue(bool(statement, "SELECT count(*)=1 FROM public.security_user_roles a"
                    + " JOIN public.security_roles r ON a.role_id=r.role_id"
                    + " WHERE r.code='SYSTEM_ADMIN' AND a.revoked_at IS NULL"));
            assertTrue(bool(statement, "SELECT count(*)=1 FROM public.security_role_assignment_history"
                    + " WHERE reason='Initial SYSTEM_ADMIN CLI bootstrap'"));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "DELETE FROM public.security_bootstrap_audit"));
        }
    }

    private static UUID bootstrap(Connection connection, String hash) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT public.epms_bootstrap_initial_admin(?, ?, ?, ?)")) {
            statement.setString(1, "First");
            statement.setString(2, "Administrator");
            statement.setString(3, "bootstrap@example.test");
            statement.setString(4, hash);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return (UUID) result.getObject(1);
            }
        }
    }

    private static boolean bool(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getBoolean(1);
        }
    }
}
