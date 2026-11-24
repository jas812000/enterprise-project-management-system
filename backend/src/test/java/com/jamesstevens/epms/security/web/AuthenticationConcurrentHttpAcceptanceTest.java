package com.jamesstevens.epms.security.web;

import com.jamesstevens.epms.organization.dto.CreateOrganizationRequest;
import com.jamesstevens.epms.organization.service.OrganizationService;
import com.jamesstevens.epms.security.service.PasswordScreeningDecisionService;
import com.jamesstevens.epms.security.service.ProvisionalCredentialIssuer;
import com.jamesstevens.epms.user.dto.CreateUserRequest;
import com.jamesstevens.epms.user.entity.UserCredential;
import com.jamesstevens.epms.user.repository.UserCredentialRepository;
import com.jamesstevens.epms.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Concurrent requests traverse the real Spring Security filter chain and PostgreSQL. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = "epms.security.audit.hmac-key=acceptance-test-only-key-not-for-production-12345")
class AuthenticationConcurrentHttpAcceptanceTest {
    private static final UUID HUMAN = UUID.fromString("0199a000-0000-7000-8000-000000000001");
    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18")
            .withInitScript("test-db-init.sql");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrganizationService organizations;
    @Autowired UserService users;
    @Autowired UserCredentialRepository credentials;
    @Autowired PasswordEncoder encoder;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean PasswordScreeningDecisionService screening;
    @MockitoBean ProvisionalCredentialIssuer provisional;

    private UUID organizationId;
    private UUID userId;
    private String email;

    private void fixture() {
        when(screening.decide(anyString()))
                .thenReturn(PasswordScreeningDecisionService.Decision.VERIFIED);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            organizationId = organizations.createOrganization(new CreateOrganizationRequest(
                    "EPMS Concurrent HTTP Acceptance", "Concurrent authentication security tests"))
                    .organizationId();
            email = "concurrent-" + UUID.randomUUID() + "@example.com";
            userId = users.createUser(new CreateUserRequest(null, null, HUMAN,
                    "CON-" + UUID.randomUUID().toString().substring(0, 12),
                    "Security", "Concurrency", email)).userId();
            credentials.saveAndFlush(new UserCredential(userId,
                    encoder.encode("Test-Only-Correct-Password-123!"), "ARGON2ID", false));
        });
    }

    private int events() {
        return jdbc.queryForObject("SELECT count(*) FROM authentication_security_events "
                + "WHERE target_user_id = ? AND event_type IN "
                + "('LOGIN_FAILED','LOGIN_LOCKED','LOGIN_REJECTED_LOCKED')",
                Integer.class, userId);
    }

    private void concurrentRejectedLogins(int requests) throws Exception {
        var ready = new CountDownLatch(requests);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(requests)) {
            List<Callable<Void>> jobs = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                jobs.add(() -> {
                    ready.countDown();
                    assertTrue(start.await(30, TimeUnit.SECONDS), "Start signal timed out");
                    mvc.perform(post("/api/auth/login").with(csrf())
                            .param("username", email).param("password", "incorrect-password"))
                            .andExpect(status().isUnauthorized());
                    return null;
                });
            }
            List<java.util.concurrent.Future<Void>> futures = new ArrayList<>();
            for (var job : jobs) futures.add(executor.submit(job));
            assertTrue(ready.await(30, TimeUnit.SECONDS), "Workers did not become ready");
            start.countDown();
            for (var future : futures) future.get(90, TimeUnit.SECONDS);
        }
    }

    @AfterEach
    void cleanup() {
        if (userId == null) return;
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("DELETE FROM authentication_security_events WHERE target_user_id = ? OR actor_user_id = ?",
                    userId, userId);
            jdbc.update("DELETE FROM account_unlock_grants WHERE user_id = ? OR granted_by = ?", userId, userId);
            jdbc.update("DELETE FROM user_credentials WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM users WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM organizations WHERE organization_id = ?", organizationId);
        });
    }

    @Test
    void concurrentHttpFailuresReachFirstLockoutWithoutLostUpdates() throws Exception {
        fixture();
        concurrentRejectedLogins(5);
        var state = credentials.findByUserId(userId).orElseThrow().getLoginLockoutState();
        assertEquals(1, state.stage());
        assertEquals(0, state.failuresInStage());
        assertNotNull(state.lockedUntil());
        assertTrue(Instant.now().isBefore(state.lockedUntil()));
        assertEquals(5, events(), "Every concurrent HTTP rejection must be audited");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM authentication_security_events "
                + "WHERE target_user_id = ? AND event_type = 'LOGIN_LOCKED'", Integer.class, userId));
    }

    @Test
    void concurrentHttpAttemptsDuringLockoutAreAuditedWithoutEscalation() throws Exception {
        fixture();
        concurrentRejectedLogins(5);
        var locked = credentials.findByUserId(userId).orElseThrow().getLoginLockoutState();
        concurrentRejectedLogins(6);
        var after = credentials.findByUserId(userId).orElseThrow().getLoginLockoutState();
        assertEquals(locked, after, "Blocked attempts must not alter lockout state");
        assertEquals(11, events(), "Each blocked HTTP attempt must be audited");
        assertEquals(6, jdbc.queryForObject("SELECT count(*) FROM authentication_security_events "
                + "WHERE target_user_id = ? AND event_type = 'LOGIN_REJECTED_LOCKED'", Integer.class, userId));
    }
}
