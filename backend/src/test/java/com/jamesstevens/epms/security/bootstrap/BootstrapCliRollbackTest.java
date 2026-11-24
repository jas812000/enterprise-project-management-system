package com.jamesstevens.epms.security.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.Console;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/** Verifies CLI-managed rollback after a JDBC function invocation fails. */
class BootstrapCliRollbackTest {
    @Test
    void failureAfterBootstrapFunctionInvocationRollsBackAndSanitizesDiagnostics() throws Exception {
        Console console = mock(Console.class);
        BootstrapPasswordPreflight preflight = mock(BootstrapPasswordPreflight.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        char[] databaseSecret = "fixture-db-password".toCharArray();
        char[] administratorSecret = "fixture-admin-password".toCharArray();
        when(console.readPassword("Bootstrap database password: ")).thenReturn(databaseSecret);
        when(console.readLine("First name: ")).thenReturn("Initial");
        when(console.readLine("Last name: ")).thenReturn("Administrator");
        when(console.readLine("Email: ")).thenReturn("initial@example.test");
        when(console.readPassword("Initial password: ")).thenReturn(administratorSecret);
        when(preflight.requireVerified(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(connection.getAutoCommit()).thenReturn(false);
        //noinspection SqlResolve
        when(connection.prepareStatement("SELECT public.epms_bootstrap_initial_admin(?, ?, ?, ?)"))
                .thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenThrow(new SQLException("private-jdbc-failure-sentinel"));
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        int status;
        try (MockedStatic<DriverManager> driver = mockStatic(DriverManager.class);
                PrintStream captured = new PrintStream(errors, true, StandardCharsets.UTF_8)) {
            driver.when(() -> DriverManager.getConnection(
                    "jdbc:" + "postgresql://127.0.0.1:1/rollback-fixture",
                    "epms_bootstrap_runtime", "fixture-db-password")).thenReturn(connection);
            System.setErr(captured);
            try {
                status = InitialAdminBootstrapCli.execute(new String[0], console, preflight,
                        "jdbc:" + "postgresql://127.0.0.1:1/rollback-fixture", "epms_bootstrap_runtime");
            } finally {
                System.setErr(previous);
            }
        }
        assertEquals(1, status);
        verify(statement).executeQuery();
        verify(connection).rollback();
        verify(connection, never()).commit();
        String output = errors.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Bootstrap failed"));
        assertFalse(output.contains("private-jdbc-failure-sentinel"));
        assertFalse(output.contains("fixture-db-password"));
        assertFalse(output.contains("fixture-admin-password"));
        for (char c : databaseSecret) {
            assertEquals('\0', c);
        }
        for (char c : administratorSecret) {
            assertEquals('\0', c);
        }
    }
}
