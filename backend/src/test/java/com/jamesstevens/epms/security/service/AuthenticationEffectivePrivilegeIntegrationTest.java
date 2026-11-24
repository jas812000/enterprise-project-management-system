package com.jamesstevens.epms.security.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

/** Tests effective PostgreSQL privileges, not merely migration text. */
@Testcontainers
class AuthenticationEffectivePrivilegeIntegrationTest {
    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18")
            .withInitScript("test-db-init.sql");

    @Test
    void applicationCannotReadOrModifyProtectedTokenRows() throws Exception {
        migrate();
        try (Connection connection = adminConnection(); Statement sql = connection.createStatement()) {
            sql.execute("SET ROLE epms_app");
            assertEquals("epms_app", currentUser(sql));
            for (String query : new String[] {
                    "SELECT token_hash FROM public.user_access_tokens LIMIT 0",
                    "SELECT session_id FROM public.user_auth_sessions LIMIT 0"}) {
                SQLException denied = assertThrows(SQLException.class, () -> sql.executeQuery(query));
                assertEquals("42501", denied.getSQLState());
            }
            SQLException insert = assertThrows(SQLException.class,
                    () -> sql.executeUpdate("INSERT INTO public.user_access_tokens DEFAULT VALUES"));
            assertEquals("42501", insert.getSQLState(), "application must lack direct INSERT privilege");
        }
    }

    @Test
    void separateRuntimeRolesCannotReadProtectedTokenMaterial() throws Exception {
        migrate();
        for (String role : new String[] {"epms_rbac_runtime", "epms_bootstrap_runtime"}) {
            try (Connection connection = adminConnection(); Statement sql = connection.createStatement()) {
                sql.execute("SET ROLE " + role);
                assertEquals(role, currentUser(sql));
                SQLException denied = assertThrows(SQLException.class,
                        () -> sql.executeQuery("SELECT token_hash FROM public.user_access_tokens LIMIT 0"));
                assertEquals("42501", denied.getSQLState(), role + " must not read token hashes");
            }
        }
    }

    @Test
    void protectedFunctionsHaveRestrictedOwnersAndEffectiveExecuteGrants() throws Exception {
        migrate();
        try (Connection connection = adminConnection(); Statement sql = connection.createStatement()) {
            for (String signature : new String[] {
                    "public.epms_auth_issue(uuid,bytea,bytea)",
                    "public.epms_auth_rotate(bytea,bytea,bytea)",
                    "public.epms_auth_revoke(uuid,text)"}) {
                try (var statement = connection.prepareStatement("""
                        SELECT pg_get_userbyid(p.proowner), p.prosecdef,
                               has_function_privilege('epms_app', p.oid, 'EXECUTE'),
                               has_function_privilege('epms_rbac_runtime', p.oid, 'EXECUTE'),
                               has_function_privilege('epms_bootstrap_runtime', p.oid, 'EXECUTE'),
                               p.proconfig @> ARRAY['search_path=pg_catalog']::text[]
                        FROM pg_proc p WHERE p.oid=CAST(? AS regprocedure)
                        """)) {
                    statement.setString(1, signature);
                    try (ResultSet result = statement.executeQuery()) {
                        assertTrue(result.next(), signature);
                        assertEquals("epms_auth_function_owner", result.getString(1));
                        assertTrue(result.getBoolean(2));
                        assertTrue(result.getBoolean(3));
                        assertFalse(result.getBoolean(4));
                        assertFalse(result.getBoolean(5));
                        assertTrue(result.getBoolean(6));
                        assertFalse(result.next());
                    }
                }
            }
        }
    }

    private static void migrate() {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword()).locations("classpath:db/migration").load().migrate();
    }

    private static Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword());
    }

    private static String currentUser(Statement sql) throws SQLException {
        try (ResultSet result = sql.executeQuery("SELECT current_user")) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }
}
