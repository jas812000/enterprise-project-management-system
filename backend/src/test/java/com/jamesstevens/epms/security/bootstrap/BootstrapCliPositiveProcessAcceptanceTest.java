package com.jamesstevens.epms.security.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real CLI Java process via a pseudo-terminal, against disposable PostgreSQL only. */
class BootstrapCliPositiveProcessAcceptanceTest {
    private static final String DATABASE_PASSWORD = "bootstrap-test-only-local-container-password";
    private static final String ADMIN_PASSWORD = "TestOnly!UniquePassword_2026#XYZ";

    @Test
    void realInteractiveCliProvisionsAndReplayFailsWithoutLeakingSecrets() throws Exception {
        try (PostgreSQLContainer db = new PostgreSQLContainer("postgres:18")
                .withInitScript("rbac-test-db-init.sql")) {
            db.start();
            Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword())
                    .locations("classpath:db/migration").load().migrate();
            try (Connection connection = DriverManager.getConnection(
                    db.getJdbcUrl(), db.getUsername(), db.getPassword());
                    Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO public.organizations(name) VALUES ('Process Test Organization')");
            }
            ProcessResult first = execute(db);
            assertEquals(0, first.code(), first.output());
            assertTrue(first.output().contains("Initial administrator created:"), first.output());
            assertNoSecrets(first.output());
            verifyDatabase(db);

            ProcessResult replay = execute(db);
            assertEquals(1, replay.code(), replay.output());
            assertTrue(replay.output().contains("Bootstrap failed"), replay.output());
            assertNoSecrets(replay.output());
            verifyDatabase(db);
        }
    }

    @Test
    void emptyDatabasePasswordExitsBeforeProvisioning() throws Exception {
        try (PostgreSQLContainer db = new PostgreSQLContainer("postgres:18")
                .withInitScript("rbac-test-db-init.sql")) {
            db.start();
            ProcessResult result = execute(db, new String[] {"", "Initial", "Administrator",
                    "initial@example.test", ADMIN_PASSWORD});
            assertEquals(2, result.code(), result.output());
            assertTrue(result.output().contains("Bootstrap database password is required"), result.output());
            assertNoSecrets(result.output());
        }
    }

    @Test
    void invalidDatabasePasswordCannotCreateBootstrapState() throws Exception {
        try (PostgreSQLContainer db = preparedDatabase()) {
            ProcessResult failed = execute(db, new String[] {"incorrect-test-password", "Initial",
                    "Administrator", "initial@example.test", ADMIN_PASSWORD});
            assertEquals(1, failed.code(), failed.output());
            assertTrue(failed.output().contains("Bootstrap failed"), failed.output());
            assertNoSecrets(failed.output());
            assertBootstrapUnconsumed(db);
        }
    }

    @Test
    void passwordScreeningOutageFailsClosedWithoutDatabaseWrites() throws Exception {
        try (PostgreSQLContainer db = preparedDatabase()) {
            ProcessResult failed = execute(db, new String[] {DATABASE_PASSWORD, "Initial",
                    "Administrator", "initial@example.test", ADMIN_PASSWORD}, "OUTAGE");
            assertEquals(1, failed.code(), failed.output());
            assertTrue(failed.output().contains("Bootstrap failed"), failed.output());
            assertFalse(failed.output().contains("Simulated screening outage"), failed.output());
            assertNoSecrets(failed.output());
            assertBootstrapUnconsumed(db);
        }
    }

    @Test
    void invalidBootstrapInputCanBeRetriedSuccessfully() throws Exception {
        try (PostgreSQLContainer db = preparedDatabase()) {
            ProcessResult rejected = execute(db, new String[] {DATABASE_PASSWORD, "   ",
                    "Administrator", "initial@example.test", ADMIN_PASSWORD});
            assertEquals(1, rejected.code(), rejected.output());
            assertTrue(rejected.output().contains("Bootstrap failed"), rejected.output());
            assertNoSecrets(rejected.output());
            assertBootstrapUnconsumed(db);

            ProcessResult accepted = execute(db);
            assertEquals(0, accepted.code(), accepted.output());
            assertTrue(accepted.output().contains("Initial administrator created:"), accepted.output());
            assertNoSecrets(accepted.output());
            verifyDatabase(db);
        }
    }

    @Test
    void nonInteractiveInvocationRejectsBeforePrompting() throws Exception {
        String javaExecutable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path");
        assertNotNull(classpath, "Surefire test classpath is required");
        ProcessBuilder builder = new ProcessBuilder(javaExecutable, "-cp", classpath,
                BootstrapCliIsolatedTestLauncher.class.getName()).redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.remove("EPMS_BOOTSTRAP_PASSWORD");
        env.remove("EPMS_TEST_BOOTSTRAP_SCREENING_MODE");
        Process process = builder.start();
        process.getOutputStream().close();
        boolean finished = process.waitFor(Duration.ofSeconds(20).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            fail("Noninteractive CLI did not terminate");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(2, process.exitValue(), output);
        assertTrue(output.contains("Bootstrap requires an interactive terminal"), output);
        assertFalse(output.contains("Bootstrap database password:"), output);
        assertNoSecrets(output);
    }

    @Test
    void cancelledInputAtEachPromptLeavesBootstrapUnconsumed() throws Exception {
        try (PostgreSQLContainer db = preparedDatabase()) {
            String[] prompts = {"First name: ", "Last name: ", "Email: ", "Initial password: "};
            for (int cancelledIndex = 1; cancelledIndex <= 4; cancelledIndex++) {
                String[] answers = {DATABASE_PASSWORD, "Initial", "Administrator",
                        "initial@example.test", ADMIN_PASSWORD};
                answers[cancelledIndex] = null;
                ProcessResult result = execute(db, answers);
                assertEquals(2, result.code(), "Cancellation at " + prompts[cancelledIndex - 1]
                        + ": " + result.output());
                assertTrue(result.output().contains("Bootstrap cancelled"), result.output());
                assertFalse(result.output().contains("Initial administrator created:"), result.output());
                assertNoSecrets(result.output());
                assertBootstrapUnconsumed(db);
            }
            ProcessResult accepted = execute(db);
            assertEquals(0, accepted.code(), accepted.output());
            assertNoSecrets(accepted.output());
            verifyDatabase(db);
        }
    }

    private static PostgreSQLContainer preparedDatabase() throws Exception {
        PostgreSQLContainer db = new PostgreSQLContainer("postgres:18")
                .withInitScript("rbac-test-db-init.sql");
        db.start();
        try {
            Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword())
                    .locations("classpath:db/migration").load().migrate();
            try (Connection connection = DriverManager.getConnection(
                    db.getJdbcUrl(), db.getUsername(), db.getPassword());
                    Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO public.organizations(name) VALUES ('Process Test Organization')");
            }
            return db;
        } catch (Exception failure) {
            db.close();
            throw failure;
        }
    }

    private static void assertBootstrapUnconsumed(PostgreSQLContainer db) throws Exception {
        try (Connection connection = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
                Statement statement = connection.createStatement()) {
            for (String table : List.of("users", "user_credentials", "security_user_roles",
                    "security_role_assignment_history", "security_bootstrap_audit")) {
                try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM public." + table)) {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getInt(1), table);
                }
            }
            try (ResultSet rows = statement.executeQuery(
                    "SELECT count(*) FROM public.security_bootstrap_state WHERE completed_at IS NOT NULL")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
        }
    }

    private static ProcessResult execute(PostgreSQLContainer db) throws Exception {
        return execute(db, new String[] {DATABASE_PASSWORD, "Initial", "Administrator",
                "initial@example.test", ADMIN_PASSWORD});
    }

    private static ProcessResult execute(PostgreSQLContainer db, String[] answers) throws Exception {
        return execute(db, answers, null);
    }

    private static ProcessResult execute(PostgreSQLContainer db, String[] answers,
            String screeningMode) throws Exception {
        String javaExecutable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path");
        assertNotNull(classpath, "Surefire test classpath is required");
        List<String> javaCommand = List.of(javaExecutable, "-cp", classpath,
                BootstrapCliIsolatedTestLauncher.class.getName());
        List<String> command = new ArrayList<>();
        command.add("script");
        command.add("-q");
        if (System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("mac")) {
            command.add("/dev/null");
            command.addAll(javaCommand);
        } else {
            command.add("-e");
            command.add("-c");
            command.add(String.join(" ", javaCommand.stream().map(BootstrapCliPositiveProcessAcceptanceTest::shellQuote).toList()));
            command.add("/dev/null");
        }
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.put("EPMS_BOOTSTRAP_JDBC_URL", db.getJdbcUrl());
        env.put("EPMS_BOOTSTRAP_USERNAME", "epms_bootstrap_runtime");
        env.remove("EPMS_BOOTSTRAP_PASSWORD");
        if (screeningMode == null) {
            env.remove("EPMS_TEST_BOOTSTRAP_SCREENING_MODE");
        } else {
            env.put("EPMS_TEST_BOOTSTRAP_SCREENING_MODE", screeningMode);
        }
        Process process = builder.start();
        StringBuilder transcript = new StringBuilder();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (java.io.InputStream input = process.getInputStream();
                    OutputStream stdin = process.getOutputStream()) {
                String[] prompts = {"Bootstrap database password: ", "First name: ", "Last name: ",
                        "Email: ", "Initial password: "};
                int step = 0;
                int value;
                while ((value = input.read()) != -1) {
                    synchronized (transcript) {
                        transcript.append((char) value);
                        if (step < prompts.length && transcript.toString().contains(prompts[step])) {
                            if (answers[step] == null) {
                                // Ctrl-D at an empty PTY line signals EOF to Java Console.
                                stdin.write(4);
                            } else {
                                stdin.write((answers[step] + "\n").getBytes(StandardCharsets.UTF_8));
                            }
                            stdin.flush();
                            step++;
                        }
                    }
                }
            } catch (java.io.IOException failure) {
                synchronized (transcript) { transcript.append("[process I/O failure]"); }
            }
        });
        boolean finished = process.waitFor(Duration.ofSeconds(40).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            fail("CLI process exceeded 40 seconds");
        }
        reader.join(5000);
        String output;
        synchronized (transcript) { output = transcript.toString(); }
        // BSD script returns the child status; GNU script -e propagates it.
        return new ProcessResult(process.exitValue(), output);
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static void assertNoSecrets(String output) {
        assertFalse(output.contains(DATABASE_PASSWORD), "Database password leaked");
        assertFalse(output.contains(ADMIN_PASSWORD), "Administrator password leaked");
    }

    private static void verifyDatabase(PostgreSQLContainer db) throws Exception {
        try (Connection connection = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
                Statement statement = connection.createStatement()) {
            for (String table : List.of("users", "user_credentials", "security_user_roles",
                    "security_role_assignment_history", "security_bootstrap_audit")) {
                try (ResultSet result = statement.executeQuery("SELECT count(*) FROM public." + table)) {
                    assertTrue(result.next());
                    assertEquals(1, result.getInt(1), table);
                }
            }
            try (ResultSet result = statement.executeQuery("SELECT c.password_algorithm_code, "
                    + "u.email, s.completed_at IS NOT NULL, a.provenance "
                    + "FROM public.users u JOIN public.user_credentials c ON c.user_id=u.user_id "
                    + "JOIN public.security_bootstrap_state s ON s.administrator_user_id=u.user_id "
                    + "JOIN public.security_bootstrap_audit a ON a.administrator_user_id=u.user_id")) {
                assertTrue(result.next());
                assertEquals("ARGON2ID", result.getString(1));
                assertEquals("initial@example.test", result.getString(2));
                assertTrue(result.getBoolean(3));
                assertEquals("EXPLICIT_ADMIN_CLI", result.getString(4));
                assertFalse(result.next());
            }
        }
    }

    private record ProcessResult(int code, String output) { }
}
