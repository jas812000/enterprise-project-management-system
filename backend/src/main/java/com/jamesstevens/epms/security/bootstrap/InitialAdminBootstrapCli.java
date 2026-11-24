package com.jamesstevens.epms.security.bootstrap;

import com.jamesstevens.epms.security.config.PasswordEncoderConfig;
import com.jamesstevens.epms.security.service.PasswordScreeningDecisionService;
import com.jamesstevens.epms.security.service.PwnedPasswordScreeningService;
import java.io.Console;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.UUID;

/** Explicit operator-only entry point; never starts the EPMS web application. */
public final class InitialAdminBootstrapCli {
    private static final String BOOTSTRAP_CANCELLED = "Bootstrap cancelled";

    private InitialAdminBootstrapCli() { }

    public static void main(String[] args) {
        BootstrapPasswordPreflight preflight = new BootstrapPasswordPreflight(
                new PasswordScreeningDecisionService(PwnedPasswordScreeningService.withHttpClient()));
        run(args, System.console(), preflight);
    }

    /** Package-private injection point for the isolated test-only process launcher.
     * PMD DoNotTerminateVM: this is a dedicated operator CLI, not the web runtime;
     * its isolated launcher tests verify process exit behavior.
     */
    @SuppressWarnings({"PMD.DoNotTerminateVM", "PMD.CommentDefaultAccessModifier"})
    static void run(String[] args, Console console, BootstrapPasswordPreflight preflight) {
        int status = execute(args, console, preflight);
        if (status != 0) {
            System.exit(status);
        }
    }

    /** Performs bootstrap without terminating the VM; all mutable secrets are cleared before return. */
    @SuppressWarnings("PMD.CommentDefaultAccessModifier")
    static int execute(String[] args, Console console, BootstrapPasswordPreflight preflight) {
        return execute(args, console, preflight,
                System.getenv("EPMS_BOOTSTRAP_JDBC_URL"), System.getenv("EPMS_BOOTSTRAP_USERNAME"));
    }

    /** Test seam for configuration validation without modifying the process environment.
     * Operator-facing error output is intentional; early returns fail closed and
     * the outer finally always clears mutable secrets. Runtime failures are
     * sanitized rather than exposing credentials or stack traces.
     */
    @SuppressWarnings({"PMD.CommentDefaultAccessModifier", "PMD.SystemPrintln",
            "PMD.OnlyOneReturn", "PMD.AvoidCatchingGenericException"})
    static int execute(String[] args, Console console, BootstrapPasswordPreflight preflight,
            String url, String user) {
        if (args.length != 0) {
            System.err.println("Bootstrap accepts no command-line arguments");
            return 2;
        }
        if (console == null) {
            System.err.println("Bootstrap requires an interactive terminal");
            return 2;
        }
        if (url == null || !url.startsWith("jdbc:postgresql:")
                || !"epms_bootstrap_runtime".equals(user)) {
            System.err.println("Restricted bootstrap database configuration is missing or invalid");
            return 2;
        }
        char[] databaseSecret = null;
        char[] entered = null;
        try {
            try {
                databaseSecret = console.readPassword("Bootstrap database password: ");
                if (databaseSecret == null || databaseSecret.length == 0) {
                    System.err.println("Bootstrap database password is required");
                    return 2;
                }
                String first = console.readLine("First name: ");
                if (first == null) {
                    System.err.println(BOOTSTRAP_CANCELLED);
                    return 2;
                }
                String last = console.readLine("Last name: ");
                if (last == null) {
                    System.err.println(BOOTSTRAP_CANCELLED);
                    return 2;
                }
                String email = console.readLine("Email: ");
                if (email == null) {
                    System.err.println(BOOTSTRAP_CANCELLED);
                    return 2;
                }
                entered = console.readPassword("Initial password: ");
                if (entered == null) {
                    System.err.println(BOOTSTRAP_CANCELLED);
                    return 2;
                }
                return provision(first, last, email, entered, databaseSecret, url, user, console, preflight);
            } catch (RuntimeException exception) {
                // Console failures must not print exception messages, stack traces, or secrets.
                System.err.println("Bootstrap input or provisioning failed");
                return 2;
            }
        } finally {
            if (entered != null) {
                Arrays.fill(entered, '\0');
            }
            if (databaseSecret != null) {
                Arrays.fill(databaseSecret, '\0');
            }
        }
    }

    /**
     * Keep rollback and sanitized CLI errors while preserving the existing
     * explicit transaction boundary. Generic JDBC exceptions must roll back.
     */
    @SuppressWarnings({"PMD.SystemPrintln", "PMD.OnlyOneReturn", "PMD.AvoidCatchingGenericException"})
    private static int provision(String first, String last, String email, char[] entered,
            char[] databaseSecret, String url, String user, Console console,
            BootstrapPasswordPreflight preflight) {
        try {
            String password = new String(entered);
            Arrays.fill(entered, '\0');
            InitialAdminProvisioner provisioner = new InitialAdminProvisioner(
                    preflight, new PasswordEncoderConfig().passwordEncoder());
            try (Connection connection = DriverManager.getConnection(url, user, new String(databaseSecret))) {
                connection.setAutoCommit(false);
                try {
                    UUID administratorId = provisioner.provision(connection, first, last, email, password);
                    connection.commit();
                    console.printf("Initial administrator created: %s%n", administratorId);
                } catch (Exception exception) {
                    connection.rollback();
                    throw exception;
                }
            }
            return 0;
        } catch (Exception exception) {
            System.err.println("Bootstrap failed; no credentials or diagnostic details were printed");
            return 1;
        }
    }

}
