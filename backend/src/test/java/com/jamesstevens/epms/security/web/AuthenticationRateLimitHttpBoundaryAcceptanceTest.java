package com.jamesstevens.epms.security.web;

import com.jamesstevens.epms.security.service.AuthenticationRateLimitService;
import com.jamesstevens.epms.security.service.PasswordScreeningDecisionService;
import com.jamesstevens.epms.security.service.ProvisionalCredentialIssuer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

/** Exercises the real HTTP filter chain and PostgreSQL counters at exact policy boundaries. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = "epms.security.audit.hmac-key=acceptance-test-only-key-not-for-production-12345")
class AuthenticationRateLimitHttpBoundaryAcceptanceTest {
    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18")
            .withInitScript("test-db-init.sql");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthenticationRateLimitService limiter;
    @MockitoBean PasswordScreeningDecisionService screening;
    @MockitoBean ProvisionalCredentialIssuer provisional;

    private final List<String> sourceKeys = new ArrayList<>();

    private String freshIp() {
        // MockMvc accepts arbitrary remote-address text; uniqueness isolates each test.
        return "2001:db8:" + UUID.randomUUID().toString().substring(0, 4)
                + ":" + UUID.randomUUID().toString().substring(0, 4) + "::1";
    }

    private String sourceKey(String remote) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remote);
        String key = limiter.sourceDigest(request);
        sourceKeys.add(key);
        return key;
    }

    private MockHttpServletRequestBuilder login(String remote) {
        return post("/api/auth/login").with(csrf())
                .with(request -> { request.setRemoteAddr(remote); request.setServletPath("/api/auth/login"); return request; })
                .param("username", "missing-" + UUID.randomUUID() + "@example.com")
                .param("password", "incorrect");
    }

    private MockHttpServletRequestBuilder protectedPost(String path, String remote) {
        return post(path).with(csrf())
                .with(request -> { request.setRemoteAddr(remote); request.setServletPath(path); return request; });
    }

    private void seedCounts(String key, String policy, int minute, int hour) {
        int changed = jdbc.update("""
                UPDATE authentication_rate_limit_counters
                SET minute_count = ?, hour_count = ?,
                    minute_window_start = date_trunc('minute', clock_timestamp()),
                    hour_window_start = date_trunc('hour', clock_timestamp()),
                    blocked_until = NULL
                WHERE source_key = ? AND policy_code = ?
                """, minute, hour, key, policy);
        assertEquals(1, changed, "An initial HTTP request must create the counter");
    }

    @AfterEach void cleanup() {
        for (String key : sourceKeys) {
            jdbc.update("DELETE FROM authentication_security_events WHERE source_ip_hmac = ?", key);
            jdbc.update("DELETE FROM authentication_rate_limit_counters WHERE source_key = ?", key);
        }
    }

    @Test void loginAllowsTwentiethRequestAndRejectsTwentyFirstAtHttpBoundary() throws Exception {
        String remote = freshIp();
        String key = sourceKey(remote);
        mvc.perform(login(remote)).andExpect(status().isUnauthorized());
        seedCounts(key, "LOGIN", 19, 19);
        mvc.perform(login(remote)).andExpect(status().isUnauthorized());
        // Seed the exact minute boundary immediately before asserting rejection.
        // A previous request may have crossed PostgreSQL's minute boundary.
        seedCounts(key, "LOGIN", 20, 20);
        var blockedAttempt = mvc.perform(login(remote)).andReturn().getResponse();
        if (blockedAttempt.getStatus() == 401) {
            // Only retry when the database clock rolled into a new minute.
            Integer minute = jdbc.queryForObject("""
                    SELECT minute_count FROM authentication_rate_limit_counters
                    WHERE source_key = ? AND policy_code = 'LOGIN'
                    """, Integer.class, key);
            assertEquals(1, minute, "Unexpected accepted request without a minute-window rollover");
            seedCounts(key, "LOGIN", 20, 20);
            blockedAttempt = mvc.perform(login(remote)).andReturn().getResponse();
        }
        assertEquals(429, blockedAttempt.getStatus(), "The 21st request must be rate limited");
        var blocked = blockedAttempt;
        assertNotNull(blocked.getHeader("Retry-After"));
        assertTrue(Long.parseLong(blocked.getHeader("Retry-After")) > 0);
        assertTrue(blocked.getContentAsString().contains("Request rate limit exceeded."));
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM authentication_security_events
                WHERE event_type = 'RATE_LIMITED' AND source_ip_hmac = ? AND reason = 'LOGIN'
                """, Integer.class, key);
        assertEquals(1, count);
    }

    @Test void loginAllowsHundredthRequestAndRejectsHundredFirstAtHttpBoundary() throws Exception {
        String remote = freshIp();
        String key = sourceKey(remote);
        mvc.perform(login(remote)).andExpect(status().isUnauthorized());
        seedCounts(key, "LOGIN", 0, 99);
        mvc.perform(login(remote)).andExpect(status().isUnauthorized());
        var blocked = mvc.perform(login(remote))
                .andExpect(status().isTooManyRequests()).andReturn().getResponse();
        assertTrue(Long.parseLong(blocked.getHeader("Retry-After")) > 0);
        Integer hour = jdbc.queryForObject("""
                SELECT hour_count FROM authentication_rate_limit_counters
                WHERE source_key = ? AND policy_code = 'LOGIN'
                """, Integer.class, key);
        assertEquals(100, hour);
    }

    @Test void passwordChangeAndAdminUnlockHaveIndependentTenPerHourHttpBoundaries() throws Exception {
        String remote = freshIp();
        String key = sourceKey(remote);
        String[][] targets = {
                {"/api/auth/change-password", "CHANGE_PASSWORD"},
                {"/api/auth/admin/users/" + UUID.randomUUID() + "/unlock", "ADMIN_UNLOCK"}
        };
        for (String[] target : targets) {
            mvc.perform(protectedPost(target[0], remote)).andExpect(status().isUnauthorized());
            seedCounts(key, target[1], 9, 9);
            mvc.perform(protectedPost(target[0], remote)).andExpect(status().isUnauthorized());
            var blocked = mvc.perform(protectedPost(target[0], remote))
                    .andExpect(status().isTooManyRequests()).andReturn().getResponse();
            assertTrue(Long.parseLong(blocked.getHeader("Retry-After")) > 0);
        }
    }

    @Test void expiredBlockAndExpiredWindowsAllowHttpRequestsAgain() throws Exception {
        String remote = freshIp();
        String key = sourceKey(remote);
        mvc.perform(login(remote)).andExpect(status().isUnauthorized());
        seedCounts(key, "LOGIN", 20, 20);
        mvc.perform(login(remote)).andExpect(status().isTooManyRequests());
        int changed = jdbc.update("""
                UPDATE authentication_rate_limit_counters
                SET blocked_until = clock_timestamp() - interval '1 second',
                    minute_window_start = date_trunc('minute', clock_timestamp()) - interval '2 minutes',
                    hour_window_start = date_trunc('hour', clock_timestamp()) - interval '2 hours'
                WHERE source_key = ? AND policy_code = 'LOGIN'
                """, key);
        assertEquals(1, changed);
        mvc.perform(login(remote)).andExpect(status().isUnauthorized());
        Integer minute = jdbc.queryForObject("""
                SELECT minute_count FROM authentication_rate_limit_counters
                WHERE source_key = ? AND policy_code = 'LOGIN'
                """, Integer.class, key);
        assertEquals(1, minute);
    }
}
