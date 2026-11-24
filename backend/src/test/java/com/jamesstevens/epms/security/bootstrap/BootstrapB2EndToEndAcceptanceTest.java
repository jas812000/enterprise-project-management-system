package com.jamesstevens.epms.security.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real PostgreSQL 18 acceptance; isolated container per test; never touches EPMS database. */
class BootstrapB2EndToEndAcceptanceTest {
    private static final String RUNTIME = "epms_bootstrap_runtime";
    private static final String PASSWORD = "bootstrap-test-only-local-container-password";
    private static final String HASH = "$argon2id$v=19$m=19456,t=2,p=1$c29tZXNhbHRzYWx0$ZmFrZWhhc2hmb3JkYnRlc3Q";
    private static final String FUNCTION = "SELECT public.epms_bootstrap_initial_admin(?, ?, ?, ?)";

    private static PostgreSQLContainer postgres() {
        PostgreSQLContainer db = new PostgreSQLContainer("postgres:18")
                .withInitScript("rbac-test-db-init.sql");
        db.start();
        Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword())
                .locations("classpath:db/migration").load().migrate();
        return db;
    }

    private static Connection connect(PostgreSQLContainer db) throws SQLException {
        return DriverManager.getConnection(db.getJdbcUrl(), RUNTIME, PASSWORD);
    }

    private static UUID invoke(Connection connection, String email, String hash) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FUNCTION)) {
            statement.setString(1, "Initial");
            statement.setString(2, "Administrator");
            statement.setString(3, email);
            statement.setString(4, hash);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return (UUID) result.getObject(1);
            }
        }
    }

    private static long count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT count(*) FROM public." + table)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static void seed(PostgreSQLContainer db) throws SQLException {
        try (Connection admin = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
                Statement statement = admin.createStatement()) {
            statement.executeUpdate("INSERT INTO public.organizations(name) VALUES ('Acceptance Organization')");
        }
    }

    @Test
    void successfulBootstrapIsCompleteAndReplayDoesNotChangeState() throws Exception {
        try (PostgreSQLContainer db = postgres()) {
            seed(db);
            UUID id;
            try (Connection runtime = connect(db)) {
                id = invoke(runtime, "initial@example.test", HASH);
                assertThrows(SQLException.class, () -> invoke(runtime, "second@example.test", HASH));
            }
            try (Connection admin = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
                    Statement statement = admin.createStatement()) {
                assertEquals(1, count(admin, "users"));
                assertEquals(1, count(admin, "user_credentials"));
                assertEquals(1, count(admin, "security_user_roles"));
                assertEquals(1, count(admin, "security_role_assignment_history"));
                assertEquals(1, count(admin, "security_bootstrap_audit"));
                try (ResultSet result = statement.executeQuery("SELECT administrator_user_id, completed_at IS NOT NULL "
                        + "FROM public.security_bootstrap_state WHERE singleton_id=1")) {
                    assertTrue(result.next());
                    assertEquals(id, result.getObject(1, UUID.class));
                    assertTrue(result.getBoolean(2));
                }
                try (ResultSet result = statement.executeQuery("SELECT c.password_algorithm_code, c.must_change_password, "
                        + "u.email, a.provenance FROM public.user_credentials c "
                        + "JOIN public.users u ON u.user_id=c.user_id "
                        + "JOIN public.security_bootstrap_audit a ON a.administrator_user_id=u.user_id")) {
                    assertTrue(result.next());
                    assertEquals("ARGON2ID", result.getString(1));
                    assertFalse(result.getBoolean(2));
                    assertEquals("initial@example.test", result.getString(3));
                    assertEquals("EXPLICIT_ADMIN_CLI", result.getString(4));
                }
            }
        }
    }

    @Test
    void failedInputAndExplicitRollbackLeaveNoPartialBootstrap() throws Exception {
        try (PostgreSQLContainer db = postgres()) {
            seed(db);
            try (Connection runtime = connect(db)) {
                assertThrows(SQLException.class, () -> invoke(runtime, "initial@example.test", "invalid"));
                runtime.setAutoCommit(false);
                UUID id = invoke(runtime, "initial@example.test", HASH);
                assertNotNull(id);
                runtime.rollback();
            }
            try (Connection admin = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
                    Statement statement = admin.createStatement()) {
                assertEquals(0, count(admin, "users"));
                assertEquals(0, count(admin, "user_credentials"));
                assertEquals(0, count(admin, "security_user_roles"));
                assertEquals(0, count(admin, "security_role_assignment_history"));
                assertEquals(0, count(admin, "security_bootstrap_audit"));
                try (ResultSet result = statement.executeQuery("SELECT completed_at IS NULL AND administrator_user_id IS NULL "
                        + "FROM public.security_bootstrap_state")) {
                    assertTrue(result.next());
                    assertTrue(result.getBoolean(1));
                }
            }
        }
    }

    @Test
    void runtimeCannotDirectlyMutateBootstrapOrRoleTables() throws Exception {
        try (PostgreSQLContainer db = postgres(); Connection runtime = connect(db);
                Statement statement = runtime.createStatement()) {
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE public.security_bootstrap_state SET completed_at=clock_timestamp() WHERE singleton_id=1"));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "DELETE FROM public.security_bootstrap_audit"));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "DELETE FROM public.security_user_roles"));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO public.users(first_name,last_name,email) VALUES ('A','B','x@example.test')"));
        }
    }

    @Test
    void concurrentAttemptsYieldExactlyOneAdministrator() throws Exception {
        try (PostgreSQLContainer db = postgres()) {
            seed(db);
            CountDownLatch start = new CountDownLatch(1);
            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                Future<Boolean> first = executor.submit(() -> attempt(db, start, "first@example.test"));
                Future<Boolean> second = executor.submit(() -> attempt(db, start, "second@example.test"));
                start.countDown();
                int successes = (first.get() ? 1 : 0) + (second.get() ? 1 : 0);
                assertEquals(1, successes);
            }
            try (Connection admin = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword())) {
                assertEquals(1, count(admin, "users"));
                assertEquals(1, count(admin, "security_user_roles"));
                assertEquals(1, count(admin, "security_bootstrap_audit"));
            }
        }
    }

    private static boolean attempt(PostgreSQLContainer db, CountDownLatch start, String email) throws Exception {
        start.await();
        try (Connection runtime = connect(db)) {
            invoke(runtime, email, HASH);
            return true;
        } catch (SQLException expected) {
            return false;
        }
    }
}
