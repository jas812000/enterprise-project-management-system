package com.jamesstevens.epms.security.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.Console;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** CLI rollback of real, uncommitted PostgreSQL bootstrap writes after an injected commit failure. */
class BootstrapCliPostWriteRollbackIntegrationTest {
    private static final String RUNTIME = "epms_bootstrap_runtime";
    private static final String DB_PASSWORD = "bootstrap-test-only-local-container-password";
    private static final String ADMIN_PASSWORD = "QG5f-Only-Strong-Test-Password-2026!";
    private static final List<String> TABLES = List.of("users", "user_credentials",
            "security_user_roles", "security_role_assignment_history", "security_bootstrap_audit");

    private static long count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT count(*) FROM public." + table)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    @Test
    void cliRollsBackRealWritesWhenCommitFailsBeforePersistence() throws Exception {
        try (PostgreSQLContainer db = new PostgreSQLContainer("postgres:18")
                .withInitScript("rbac-test-db-init.sql")) {
            db.start();
            Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword())
                    .locations("classpath:db/migration").load().migrate();
            try (Connection admin = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
                    Statement statement = admin.createStatement()) {
                statement.executeUpdate("INSERT INTO public.organizations(name) VALUES ('Rollback Test Organization')");
            }

            Console console = mock(Console.class);
            BootstrapPasswordPreflight preflight = mock(BootstrapPasswordPreflight.class);
            char[] dbSecret = DB_PASSWORD.toCharArray();
            char[] adminSecret = ADMIN_PASSWORD.toCharArray();
            when(console.readPassword("Bootstrap database password: ")).thenReturn(dbSecret);
            when(console.readLine("First name: ")).thenReturn("Initial");
            when(console.readLine("Last name: ")).thenReturn("Administrator");
            when(console.readLine("Email: ")).thenReturn("rollback@example.test");
            when(console.readPassword("Initial password: ")).thenReturn(adminSecret);
            when(preflight.requireVerified(anyString())).thenAnswer(call -> call.getArgument(0));

            try (Connection physical = DriverManager.getConnection(db.getJdbcUrl(), RUNTIME, DB_PASSWORD)) {
                Connection intercepted = spy(physical);
                doAnswer(call -> {
                    for (String table : TABLES) {
                        assertEquals(1L, count(physical, table), "Write not reached before commit: " + table);
                    }
                    assertEquals(1L, count(physical, "security_bootstrap_state"));
                    try (Statement state = physical.createStatement();
                            ResultSet result = state.executeQuery("SELECT completed_at IS NOT NULL "
                                    + "FROM public.security_bootstrap_state WHERE singleton_id=1")) {
                        assertTrue(result.next());
                        assertTrue(result.getBoolean(1), "Bootstrap state not updated before commit");
                    }
                    throw new SQLException("private-post-write-commit-failure-sentinel");
                }).when(intercepted).commit();

                ByteArrayOutputStream errors = new ByteArrayOutputStream();
                PrintStream previous = System.err;
                int status;
                try (MockedStatic<DriverManager> driver = mockStatic(DriverManager.class);
                        PrintStream captured = new PrintStream(errors, true, StandardCharsets.UTF_8)) {
                    driver.when(() -> DriverManager.getConnection(db.getJdbcUrl(), RUNTIME, DB_PASSWORD))
                            .thenReturn(intercepted);
                    System.setErr(captured);
                    try {
                        status = InitialAdminBootstrapCli.execute(new String[0], console, preflight,
                                db.getJdbcUrl(), RUNTIME);
                    } finally {
                        System.setErr(previous);
                    }
                }
                assertEquals(1, status);
                verify(intercepted).rollback();
                String output = errors.toString(StandardCharsets.UTF_8);
                assertTrue(output.contains("Bootstrap failed"));
                assertFalse(output.contains("private-post-write-commit-failure-sentinel"));
                assertFalse(output.contains(DB_PASSWORD));
                assertFalse(output.contains(ADMIN_PASSWORD));
                for (char c : dbSecret) {
                    assertEquals('\0', c, "Database secret not cleared");
                }
                for (char c : adminSecret) {
                    assertEquals('\0', c, "Administrator secret not cleared");
                }
            }
            try (Connection admin = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword())) {
                for (String table : TABLES) {
                    assertEquals(0L, count(admin, table), "Partial bootstrap survived rollback: " + table);
                }
                try (Statement state = admin.createStatement();
                        ResultSet result = state.executeQuery("SELECT completed_at IS NULL "
                                + "AND administrator_user_id IS NULL FROM public.security_bootstrap_state "
                                + "WHERE singleton_id=1")) {
                    assertTrue(result.next());
                    assertTrue(result.getBoolean(1), "Bootstrap state consumed despite rollback");
                }
            }
        }
    }
}
