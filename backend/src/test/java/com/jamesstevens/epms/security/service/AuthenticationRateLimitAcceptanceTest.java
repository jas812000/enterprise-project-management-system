package com.jamesstevens.epms.security.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** Acceptance coverage for actual PostgreSQL counters and mandatory privacy-preserving audit. */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = "epms.security.audit.hmac-key=acceptance-test-only-key-not-for-production-12345")
class AuthenticationRateLimitAcceptanceTest {
    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18")
            .withInitScript("test-db-init.sql");

    @Autowired AuthenticationRateLimitCounterRepository counters;
    @Autowired AuthenticationRateLimitService limiter;
    @Autowired AuthenticationSecurityAuditService audit;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PasswordScreeningDecisionService screening;
    @MockitoBean ProvisionalCredentialIssuer provisional;
    private final ArrayList<String> sources = new ArrayList<>();
    private final ArrayList<UUID> events = new ArrayList<>();

    private String source() {
        String key = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        sources.add(key);
        return key;
    }

    @AfterEach void cleanup() {
        for (UUID event : events) {
            jdbc.update("DELETE FROM authentication_security_events WHERE correlation_id = ?", event);
        }
        for (String key : sources) {
            jdbc.update("DELETE FROM authentication_rate_limit_counters WHERE source_key = ?", key);
        }
    }

    @Test void loginHourlyBoundaryIsExactlyOneHundred() {
        String key = source();
        for (int i = 1; i <= 100; i++) {
            var allowed = counters.consume(key, "LOGIN", 200, 100, Duration.ofMinutes(5));
            assertTrue(allowed.allowed(), "request " + i);
        }
        var rejected = counters.consume(key, "LOGIN", 200, 100, Duration.ofMinutes(5));
        assertFalse(rejected.allowed());
        assertEquals(100, rejected.hourCount());
        // The minute counter resets at a database-clock minute boundary, which
        // may occur while the 100 hourly requests are being consumed.
        assertTrue(rejected.minuteCount() >= 1 && rejected.minuteCount() <= 100);
        assertNotNull(rejected.retryAt());
    }

    @Test void passwordChangeAndUnlockHaveIndependentTenRequestBoundaries() {
        String key = source();
        for (String policy : new String[]{"CHANGE_PASSWORD", "ADMIN_UNLOCK"}) {
            for (int i = 0; i < 10; i++) {
                assertTrue(counters.consume(key, policy, 10, 10, Duration.ofMinutes(5)).allowed());
            }
            var rejected = counters.consume(key, policy, 10, 10, Duration.ofMinutes(5));
            assertFalse(rejected.allowed());
            assertEquals(10, rejected.hourCount());
        }
    }

    @Test void expiredBlockAllowsNewWindowWithoutWaitingFiveMinutes() {
        String key = source();
        assertTrue(counters.consume(key, "LOGIN", 1, 1, Duration.ofMinutes(5)).allowed());
        assertFalse(counters.consume(key, "LOGIN", 1, 1, Duration.ofMinutes(5)).allowed());
        jdbc.update("""
                UPDATE authentication_rate_limit_counters
                SET blocked_until = clock_timestamp() - interval '1 second',
                    minute_window_start = date_trunc('minute', clock_timestamp()) - interval '2 minutes',
                    hour_window_start = date_trunc('hour', clock_timestamp()) - interval '2 hours'
                WHERE source_key = ? AND policy_code = 'LOGIN'
                """, key);
        var next = counters.consume(key, "LOGIN", 1, 1, Duration.ofMinutes(5));
        assertTrue(next.allowed());
        assertEquals(1, next.minuteCount());
        assertEquals(1, next.hourCount());
    }

    @Test void rateLimitedAuditPersistsDigestAndNeverRawSourceIp() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.57");
        request.addHeader("X-Forwarded-For", "198.51.100.44");
        UUID correlation = UUID.randomUUID();
        events.add(correlation);
        request.setAttribute("epms.correlationId", correlation);
        String digest = limiter.sourceDigest(request);
        audit.recordRateLimited("LOGIN", digest, request);
        var row = jdbc.queryForMap("""
                SELECT event_type, source_ip_hmac, source_ip::text AS source_ip,
                       account_lookup_hmac, correlation_id, reason, outcome
                FROM authentication_security_events WHERE correlation_id = ?
                """, correlation);
        assertEquals("RATE_LIMITED", row.get("event_type"));
        assertEquals(digest, row.get("source_ip_hmac"));
        assertNull(row.get("source_ip"));
        assertNull(row.get("account_lookup_hmac"));
        assertEquals(correlation, row.get("correlation_id"));
        assertEquals("LOGIN", row.get("reason"));
        assertEquals("REJECTED", row.get("outcome"));
    }

    @Test void forwardedHeaderDoesNotChangeIdentityAndDifferentIpDoes() {
        var first = new MockHttpServletRequest();
        first.setRemoteAddr("192.0.2.57");
        first.addHeader("X-Forwarded-For", "203.0.113.2");
        var second = new MockHttpServletRequest();
        second.setRemoteAddr("192.0.2.57");
        second.addHeader("X-Forwarded-For", "198.51.100.99");
        var third = new MockHttpServletRequest();
        third.setRemoteAddr("192.0.2.58");
        assertEquals(limiter.sourceDigest(first), limiter.sourceDigest(second));
        assertNotEquals(limiter.sourceDigest(first), limiter.sourceDigest(third));
        assertTrue(limiter.sourceDigest(first).matches("[0-9a-f]{64}"));
    }
}
