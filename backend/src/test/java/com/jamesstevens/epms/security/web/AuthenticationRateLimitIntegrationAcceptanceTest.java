package com.jamesstevens.epms.security.web;

import com.jamesstevens.epms.security.service.AuthenticationRateLimitService;
import com.jamesstevens.epms.security.service.PasswordScreeningDecisionService;
import com.jamesstevens.epms.security.service.ProvisionalCredentialIssuer;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Integration acceptance of CSRF ordering, source identity, blocking and mandatory audit. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = "epms.security.audit.hmac-key=acceptance-test-only-key-not-for-production-12345")
class AuthenticationRateLimitIntegrationAcceptanceTest {
    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18")
            .withInitScript("test-db-init.sql");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthenticationRateLimitService limiter;
    @MockitoBean PasswordScreeningDecisionService screening;
    @MockitoBean ProvisionalCredentialIssuer provisional;

    private final String remote = "2001:db8:" + UUID.randomUUID().toString().substring(0, 4)
            + ":" + UUID.randomUUID().toString().substring(0, 4) + "::9";
    private static final String TRIGGER = "epms_test_rate_limit_audit_outage";

    private String key() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(remote);
        return limiter.sourceDigest(request);
    }

    private MockHttpServletRequestBuilder login(boolean includeCsrf, String forwarded) {
        var request = post("/api/auth/login")
                .with(r -> { r.setRemoteAddr(remote); r.setServletPath("/api/auth/login"); return r; })
                .param("username", "absent-" + UUID.randomUUID() + "@example.com")
                .param("password", "incorrect");
        if (includeCsrf) request.with(csrf());
        if (forwarded != null) request.header("X-Forwarded-For", forwarded);
        return request;
    }

    private int count() {
        return jdbc.queryForObject("SELECT count(*) FROM authentication_rate_limit_counters WHERE source_key = ? AND policy_code = 'LOGIN'",
                Integer.class, key());
    }

    private int minuteCount() {
        return jdbc.queryForObject("SELECT minute_count FROM authentication_rate_limit_counters WHERE source_key = ? AND policy_code = 'LOGIN'",
                Integer.class, key());
    }

    private void seedLimit() throws Exception {
        mvc.perform(login(true, null)).andExpect(status().isUnauthorized());
        assertEquals(1, jdbc.update("""
                UPDATE authentication_rate_limit_counters SET minute_count = 20, hour_count = 20,
                    minute_window_start = date_trunc('minute', clock_timestamp()),
                    hour_window_start = date_trunc('hour', clock_timestamp()), blocked_until = NULL
                WHERE source_key = ? AND policy_code = 'LOGIN'
                """, key()));
    }

    private void disableAuditFailure() {
        jdbc.execute("DROP TRIGGER IF EXISTS " + TRIGGER + " ON authentication_security_events");
        jdbc.execute("DROP FUNCTION IF EXISTS epms_test_rate_limit_audit_reject()");
    }

    @AfterEach void cleanup() {
        disableAuditFailure();
        jdbc.update("DELETE FROM authentication_security_events WHERE source_ip_hmac = ?", key());
        jdbc.update("DELETE FROM authentication_rate_limit_counters WHERE source_key = ?", key());
    }

    @Test void csrfRejectionDoesNotConsumeRateLimitCounter() throws Exception {
        mvc.perform(login(false, null)).andExpect(status().isForbidden());
        assertEquals(0, count(), "CSRF rejection must precede rate-limit accounting");
    }

    @Test void forwardedSpoofCannotEscapeActiveBlockAndBlockDoesNotIncrementCounter() throws Exception {
        seedLimit();
        var first = mvc.perform(login(true, "203.0.113.1"))
                .andExpect(status().isTooManyRequests()).andReturn().getResponse();
        long firstRetry = Long.parseLong(first.getHeader("Retry-After"));
        assertTrue(firstRetry >= 1 && firstRetry <= 301);
        var second = mvc.perform(login(true, "198.51.100.200"))
                .andExpect(status().isTooManyRequests()).andReturn().getResponse();
        long secondRetry = Long.parseLong(second.getHeader("Retry-After"));
        assertTrue(secondRetry >= 1 && secondRetry <= firstRetry + 1);
        assertEquals(20, minuteCount(), "Blocked requests must not increment the counter");
        assertEquals(2, jdbc.queryForObject("""
                SELECT count(*) FROM authentication_security_events
                WHERE source_ip_hmac = ? AND event_type = 'RATE_LIMITED' AND reason = 'LOGIN'
                """, Integer.class, key()));
    }

    @Test void failedMandatoryRateLimitAuditReturns503WithoutLeakingStorageError() throws Exception {
        seedLimit();
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION epms_test_rate_limit_audit_reject()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.event_type = 'RATE_LIMITED' THEN
                        RAISE EXCEPTION 'simulated sensitive rate-limit audit outage';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        jdbc.execute("CREATE TRIGGER " + TRIGGER + " BEFORE INSERT ON authentication_security_events "
                + "FOR EACH ROW EXECUTE FUNCTION epms_test_rate_limit_audit_reject()");
        var response = mvc.perform(login(true, null))
                .andExpect(status().isServiceUnavailable()).andReturn().getResponse();
        assertNull(response.getHeader("Retry-After"));
        assertTrue(response.getContentAsString().contains("Authentication is temporarily unavailable."));
        assertFalse(response.getContentAsString().contains("simulated sensitive"));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM authentication_security_events
                WHERE source_ip_hmac = ? AND event_type = 'RATE_LIMITED'
                """, Integer.class, key()));
    }
}
