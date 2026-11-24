package com.jamesstevens.epms.security.bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayOutputStream;
import java.io.Console;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Fault injection for every console read; no database or network access. */
class BootstrapCliInputFailureTest {
    private static final String SENTINEL = "private-fault-diagnostic-should-not-leak";

    @Test
    void everyConsoleReadFailureReturnsGenericErrorAndClearsAvailableSecrets() {
        for (int failureIndex = 0; failureIndex < 5; failureIndex++) {
            Console console = mock(Console.class);
            char[] databasePassword = "sensitive-database-password".toCharArray();
            char[] administratorPassword = "sensitive-admin-password".toCharArray();
            if (failureIndex == 0) {
                when(console.readPassword("Bootstrap database password: "))
                        .thenThrow(new IllegalStateException(SENTINEL));
            } else {
                when(console.readPassword("Bootstrap database password: ")).thenReturn(databasePassword);
                if (failureIndex == 1) {
                    when(console.readLine("First name: ")).thenThrow(new IllegalStateException(SENTINEL));
                } else {
                    when(console.readLine("First name: ")).thenReturn("Initial");
                    if (failureIndex == 2) {
                        when(console.readLine("Last name: ")).thenThrow(new IllegalStateException(SENTINEL));
                    } else {
                        when(console.readLine("Last name: ")).thenReturn("Administrator");
                        if (failureIndex == 3) {
                            when(console.readLine("Email: ")).thenThrow(new IllegalStateException(SENTINEL));
                        } else {
                            when(console.readLine("Email: ")).thenReturn("initial@example.test");
                            when(console.readPassword("Initial password: "))
                                    .thenThrow(new IllegalStateException(SENTINEL));
                        }
                    }
                }
            }
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            PrintStream previous = System.err;
            int status;
            try (PrintStream captured = new PrintStream(errors, true, StandardCharsets.UTF_8)) {
                System.setErr(captured);
                status = InitialAdminBootstrapCli.execute(new String[0], console, null,
                        "jdbc:" + "postgresql://127.0.0.1:1/never-connected", "epms_bootstrap_runtime");
            } finally {
                System.setErr(previous);
            }
            assertEquals(2, status, "prompt " + failureIndex);
            String output = errors.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("Bootstrap input or provisioning failed"), output);
            assertFalse(output.contains(SENTINEL), output);
            assertFalse(output.contains("sensitive-"), output);
            if (failureIndex > 0) {
                for (char value : databasePassword) {
                    assertEquals('\0', value, "database password not cleared at prompt " + failureIndex);
                }
            }
        }
    }

    @Test
    void connectionFailureAfterBothPasswordsAreReadClearsArraysAndSanitizesOutput() {
        Console console = mock(Console.class);
        char[] databasePassword = "database-secret-sentinel".toCharArray();
        char[] administratorPassword = "administrator-secret-sentinel".toCharArray();
        when(console.readPassword("Bootstrap database password: ")).thenReturn(databasePassword);
        when(console.readLine("First name: ")).thenReturn("Initial");
        when(console.readLine("Last name: ")).thenReturn("Administrator");
        when(console.readLine("Email: ")).thenReturn("initial@example.test");
        when(console.readPassword("Initial password: ")).thenReturn(administratorPassword);
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        int status;
        try (PrintStream captured = new PrintStream(errors, true, StandardCharsets.UTF_8)) {
            System.setErr(captured);
            status = InitialAdminBootstrapCli.execute(new String[0], console, null,
                    "jdbc:" + "postgresql://127.0.0.1:1/never-connected", "epms_bootstrap_runtime");
        } finally {
            System.setErr(previous);
        }
        assertEquals(1, status);
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("Bootstrap failed"));
        assertFalse(errors.toString(StandardCharsets.UTF_8).contains("secret-sentinel"));
        for (char value : databasePassword) {
            assertEquals('\0', value, "database password not cleared");
        }
        for (char value : administratorPassword) {
            assertEquals('\0', value, "administrator password not cleared");
        }
    }

    @Test
    void cancelledAdministratorPasswordClearsDatabasePassword() {
        Console console = mock(Console.class);
        char[] databasePassword = "database-secret-sentinel".toCharArray();
        when(console.readPassword("Bootstrap database password: ")).thenReturn(databasePassword);
        when(console.readLine("First name: ")).thenReturn("Initial");
        when(console.readLine("Last name: ")).thenReturn("Administrator");
        when(console.readLine("Email: ")).thenReturn("initial@example.test");
        when(console.readPassword("Initial password: ")).thenReturn(null);
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        int status;
        try (PrintStream captured = new PrintStream(errors, true, StandardCharsets.UTF_8)) {
            System.setErr(captured);
            status = InitialAdminBootstrapCli.execute(new String[0], console, null,
                    "jdbc:" + "postgresql://127.0.0.1:1/never-connected", "epms_bootstrap_runtime");
        } finally {
            System.setErr(previous);
        }
        assertEquals(2, status);
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("Bootstrap cancelled"));
        for (char value : databasePassword) {
            assertEquals('\0', value, "database password not cleared");
        }
    }
}
