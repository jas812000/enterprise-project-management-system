package com.jamesstevens.epms.security.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = "epms.security.audit.hmac-key=acceptance-test-only-key-not-for-production-12345")
class AuthenticationRateLimitCounterRepositoryTest {
    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18")
            .withInitScript("test-db-init.sql");
    @Autowired AuthenticationRateLimitCounterRepository counters;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean PasswordScreeningDecisionService screening;
    @MockitoBean ProvisionalCredentialIssuer provisional;
    private final List<String> sourceKeys = new ArrayList<>();

    private String key() {
        String value = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        sourceKeys.add(value);
        return value;
    }

    private void waitForSafeMinuteWindow() throws InterruptedException {
        while (true) {
            var databaseTime = jdbc.queryForObject(
                    "SELECT clock_timestamp()",
                    java.time.OffsetDateTime.class
            );

            if (databaseTime == null) {
                throw new IllegalStateException("Database clock unavailable");
            }

            int seconds = databaseTime.getSecond();

            if (seconds >= 5 && seconds <= 45) {
                return;
            }

            Thread.sleep(250);
        }
    }

    @AfterEach void cleanup() {
        for (String source : sourceKeys) {
            jdbc.update("DELETE FROM authentication_rate_limit_counters WHERE source_key = ?", source);
        }
    }

    @Test void twentyRequestsAllowedAndTwentyFirstBlockedWithoutIncreasingCount() {
        String source = key();
        for (int i = 0; i < 20; i++) {
            assertTrue(counters.consume(source, "LOGIN", 20, 100, Duration.ofMinutes(5)).allowed());
        }
        var denied = counters.consume(source, "LOGIN", 20, 100, Duration.ofMinutes(5));
        assertFalse(denied.allowed());
        assertNotNull(denied.retryAt());
        assertEquals(20, denied.minuteCount());
        assertEquals(20, denied.hourCount());
        assertFalse(counters.consume(source, "LOGIN", 20, 100, Duration.ofMinutes(5)).allowed());
    }

    @Test void separateSourcesAndPoliciesHaveIndependentCounters() {
        String first = key();
        String second = key();
        assertTrue(counters.consume(first, "LOGIN", 1, 2, Duration.ofMinutes(5)).allowed());
        assertFalse(counters.consume(first, "LOGIN", 1, 2, Duration.ofMinutes(5)).allowed());
        assertTrue(counters.consume(second, "LOGIN", 1, 2, Duration.ofMinutes(5)).allowed());
        assertTrue(counters.consume(first, "PASSWORD_CHANGE", 1, 2, Duration.ofMinutes(5)).allowed());
    }

    @Test void concurrentRequestsCannotExceedThreshold() throws Exception {
        waitForSafeMinuteWindow();
        String source = key();
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                tasks.add(() -> counters.consume(source, "LOGIN", 20, 100,
                        Duration.ofMinutes(5)).allowed());
            }
            int allowed = 0;
            for (var future : executor.invokeAll(tasks)) {
                if (future.get(60, TimeUnit.SECONDS)) allowed++;
            }
            assertEquals(20, allowed);
        }
        assertEquals(20, jdbc.queryForObject("""
                SELECT minute_count FROM authentication_rate_limit_counters
                WHERE source_key = ? AND policy_code = 'LOGIN'
                """, Integer.class, source));
    }

    @Test void rejectsInvalidKeysBeforeDatabaseWrite() {
        var invalidSource = assertThrows(InvalidDataAccessApiUsageException.class,
                () -> counters.consume("192.0.2.1", "LOGIN", 20, 100, Duration.ofMinutes(5)));
        assertInstanceOf(IllegalArgumentException.class, invalidSource.getCause());
        var invalidPolicy = assertThrows(InvalidDataAccessApiUsageException.class,
                () -> counters.consume("a".repeat(64), "login", 20, 100, Duration.ofMinutes(5)));
        assertInstanceOf(IllegalArgumentException.class, invalidPolicy.getCause());
    }

    /** Separate repository objects simulate independent application workers using one database. */
    @Test void separateRepositoryInstancesSharePostgresCounter() {
        String source = key();
        var second = new AuthenticationRateLimitCounterRepository(jdbc, transactionManager);
        for (int i = 0; i < 20; i++) {
            var worker = i % 2 == 0 ? counters : second;
            assertTrue(worker.consume(source, "LOGIN", 20, 100, Duration.ofMinutes(5)).allowed());
        }
        assertFalse(second.consume(source, "LOGIN", 20, 100, Duration.ofMinutes(5)).allowed());
        assertFalse(counters.consume(source, "LOGIN", 20, 100, Duration.ofMinutes(5)).allowed());
        assertEquals(20, jdbc.queryForObject("""
                SELECT minute_count FROM authentication_rate_limit_counters
                WHERE source_key = ? AND policy_code = 'LOGIN'
                """, Integer.class, source));
    }

    @Test void separateRepositoryInstancesEnforceCombinedConcurrentThreshold() throws Exception {
        waitForSafeMinuteWindow();
        String source = key();
        var second = new AuthenticationRateLimitCounterRepository(jdbc, transactionManager);
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                var worker = i % 2 == 0 ? counters : second;
                tasks.add(() -> worker.consume(source, "LOGIN", 20, 100,
                        Duration.ofMinutes(5)).allowed());
            }
            int allowed = 0;
            for (var future : executor.invokeAll(tasks)) {
                if (future.get(60, TimeUnit.SECONDS)) allowed++;
            }
            assertEquals(20, allowed);
        }
        assertEquals(20, jdbc.queryForObject("""
                SELECT minute_count FROM authentication_rate_limit_counters
                WHERE source_key = ? AND policy_code = 'LOGIN'
                """, Integer.class, source));
    }

    @Test void expiredSharedBlockIsVisibleToSecondRepositoryInstance() {
        String source = key();
        var second = new AuthenticationRateLimitCounterRepository(jdbc, transactionManager);
        assertTrue(counters.consume(source, "LOGIN", 1, 1, Duration.ofMinutes(5)).allowed());
        assertFalse(second.consume(source, "LOGIN", 1, 1, Duration.ofMinutes(5)).allowed());
        assertFalse(counters.consume(source, "LOGIN", 1, 1, Duration.ofMinutes(5)).allowed());
        jdbc.update("""
                UPDATE authentication_rate_limit_counters
                SET blocked_until = clock_timestamp() - interval '1 second',
                    minute_window_start = date_trunc('minute', clock_timestamp()) - interval '2 minutes',
                    hour_window_start = date_trunc('hour', clock_timestamp()) - interval '2 hours'
                WHERE source_key = ? AND policy_code = 'LOGIN'
                """, source);
        var allowed = second.consume(source, "LOGIN", 1, 1, Duration.ofMinutes(5));
        assertTrue(allowed.allowed());
        assertEquals(1, allowed.minuteCount());
        assertEquals(1, allowed.hourCount());
    }

}
