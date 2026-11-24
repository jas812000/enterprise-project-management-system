package com.jamesstevens.epms.security.web;

import com.jamesstevens.epms.organization.dto.CreateOrganizationRequest;
import com.jamesstevens.epms.organization.service.OrganizationService;
import com.jamesstevens.epms.user.dto.CreateUserRequest;
import com.jamesstevens.epms.user.entity.UserCredential;
import com.jamesstevens.epms.user.repository.UserCredentialRepository;
import com.jamesstevens.epms.user.service.UserService;
import com.jamesstevens.epms.security.service.PasswordScreeningDecisionService;
import com.jamesstevens.epms.security.service.ProvisionalCredentialIssuer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Authenticated filter-chain authorization and transactional audit acceptance. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = "epms.security.audit.hmac-key=acceptance-test-only-key-not-for-production-12345")
class AccountUnlockAuthenticatedHttpAcceptanceTest {
    private static final UUID HUMAN = UUID.fromString("0199a000-0000-7000-8000-000000000001");
    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18")
            .withInitScript("test-db-init.sql");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.jamesstevens.epms.security.service.AuthenticationRateLimitService rateLimiter;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired OrganizationService organizations;
    @Autowired UserService users;
    @Autowired UserCredentialRepository credentials;
    @Autowired PlatformTransactionManager transactions;
    @Autowired com.jamesstevens.epms.security.service.AccountUnlockService accountUnlockService;
    @MockitoBean PasswordScreeningDecisionService screening;
    @MockitoBean ProvisionalCredentialIssuer provisional;
    UUID organizationId;
    UUID actorId;
    UUID targetId;
    String actorEmail;
    private static final String ACTOR_PASSWORD = "Test-Only-Auth-Password-123!";

    private void fixture() {
        when(screening.decide(anyString())).thenReturn(PasswordScreeningDecisionService.Decision.VERIFIED);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            organizationId = organizations.createOrganization(new CreateOrganizationRequest(
                    "EPMS HTTP Unlock Acceptance", "Authorization verification")).organizationId();
            actorEmail = "actor-" + UUID.randomUUID() + "@example.com";
            actorId = users.createUser(new CreateUserRequest(null, null, HUMAN,
                    "AUTH-" + UUID.randomUUID().toString().substring(0, 12),
                    "Security", "Administrator", actorEmail)).userId();
            targetId = users.createUser(new CreateUserRequest(null, null, HUMAN,
                    "AUTH-" + UUID.randomUUID().toString().substring(0, 12),
                    "Security", "Target", "target-" + UUID.randomUUID() + "@example.com")).userId();
            credentials.saveAndFlush(new UserCredential(actorId, passwordEncoder.encode(ACTOR_PASSWORD), "ARGON2ID", false));
            credentials.saveAndFlush(new UserCredential(targetId, "encoded-test-hash", "ARGON2ID", false));
        });
    }

    /** Log in through the real Spring Security form-login filter. */
    private MockHttpSession authenticatedSession() throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                .param("username", actorEmail).param("password", ACTOR_PASSWORD))
                .andExpect(status().isOk()).andReturn();
        var session = result.getRequest().getSession(false);
        assertNotNull(session, "Login must establish a session");
        assertTrue(session instanceof MockHttpSession);
        assertNotNull(session.getAttribute("EPMS_PASSWORD_CHANGED_AT"));
        return (MockHttpSession) session;
    }

    private String endpoint() { return "/api/auth/admin/users/" + targetId + "/unlock"; }
    private int eventCount(String type) {
        return jdbc.queryForObject("SELECT count(*) FROM authentication_security_events WHERE target_user_id = ? AND event_type = ?",
                Integer.class, targetId, type);
    }

    @AfterEach void cleanup() {
        disableUnlockAuditFailure();
        if (actorId == null) return;
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("DELETE FROM authentication_security_events WHERE actor_user_id IN (?, ?) OR target_user_id IN (?, ?)",
                    actorId, targetId, actorId, targetId);
            jdbc.update("DELETE FROM account_unlock_grants WHERE user_id IN (?, ?) OR granted_by IN (?, ?)",
                    actorId, targetId, actorId, targetId);
            jdbc.update("DELETE FROM user_credentials WHERE user_id IN (?, ?)", actorId, targetId);
            jdbc.update("DELETE FROM users WHERE user_id IN (?, ?)", actorId, targetId);
            jdbc.update("DELETE FROM organizations WHERE organization_id = ?", organizationId);
        });
    }

    @Test void authenticatedWithoutGrantIsForbiddenAndAudited() throws Exception {
        fixture();
        mvc.perform(post(endpoint()).session(authenticatedSession()).with(csrf())
                .contentType("application/json").content("{\"reason\":\"incident reviewed\"}"))
                .andExpect(status().isForbidden());
        assertEquals(1, eventCount("ACCOUNT_UNLOCK_REJECTED"));
        assertEquals(0, eventCount("ACCOUNT_UNLOCK"));
    }

    @Test void explicitGrantAllowsUnlockAndPersistsActorAndReason() throws Exception {
        fixture();
        jdbc.update("INSERT INTO account_unlock_grants(user_id) VALUES (?)", actorId);
        mvc.perform(post(endpoint()).session(authenticatedSession()).with(csrf())
                .contentType("application/json").content("{\"reason\":\"incident reviewed\"}"))
                .andExpect(status().isNoContent());
        assertEquals(1, eventCount("ACCOUNT_UNLOCK"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM authentication_security_events " +
                "WHERE target_user_id = ? AND actor_user_id = ? AND event_type = 'ACCOUNT_UNLOCK' AND reason = 'incident reviewed'",
                Integer.class, targetId, actorId));
    }

    @Test void missingReasonIsForbiddenAndAudited() throws Exception {
        fixture();
        jdbc.update("INSERT INTO account_unlock_grants(user_id) VALUES (?)", actorId);
        mvc.perform(post(endpoint()).session(authenticatedSession()).with(csrf())
                .contentType("application/json").content("{\"reason\":\"\"}"))
                .andExpect(status().isForbidden());
        assertEquals(1, eventCount("ACCOUNT_UNLOCK_REJECTED"));
        assertEquals(0, eventCount("ACCOUNT_UNLOCK"));
    }

    @Test void grantedActorStillRequiresCsrf() throws Exception {
        fixture();
        jdbc.update("INSERT INTO account_unlock_grants(user_id) VALUES (?)", actorId);
        mvc.perform(post(endpoint()).session(authenticatedSession())
                .contentType("application/json").content("{\"reason\":\"incident reviewed\"}"))
                .andExpect(status().isForbidden());
        assertEquals(0, eventCount("ACCOUNT_UNLOCK"));
    }

    private static final String AUDIT_OUTAGE_TRIGGER = "epms_test_unlock_audit_outage";

    private void enableUnlockAuditFailure() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION epms_test_reject_unlock_audit()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  RAISE EXCEPTION 'simulated unlock audit persistence outage';
                END;
                $$
                """);
        jdbc.execute("CREATE TRIGGER " + AUDIT_OUTAGE_TRIGGER
                + " BEFORE INSERT ON authentication_security_events "
                + "FOR EACH ROW WHEN (NEW.event_type IN ('ACCOUNT_UNLOCK', 'ACCOUNT_UNLOCK_REJECTED')) "
                + "EXECUTE FUNCTION epms_test_reject_unlock_audit()");
    }

    private void disableUnlockAuditFailure() {
        jdbc.execute("DROP TRIGGER IF EXISTS " + AUDIT_OUTAGE_TRIGGER
                + " ON authentication_security_events");
        jdbc.execute("DROP FUNCTION IF EXISTS epms_test_reject_unlock_audit()");
    }

    private void permanentlyLockTarget() {
        jdbc.update("""
                UPDATE user_credentials
                SET login_lockout_stage = 3, login_stage_failures = 0,
                    login_locked_until = NULL, login_admin_unlock_required = TRUE
                WHERE user_id = ?
                """, targetId);
    }

    private boolean targetRequiresAdministratorUnlock() {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT login_admin_unlock_required FROM user_credentials WHERE user_id = ?
                """, Boolean.class, targetId));
    }

    @Test void unlockAuditOutageRollsBackAndRestoredAuditAllowsRetry() throws Exception {
        fixture();
        jdbc.update("INSERT INTO account_unlock_grants(user_id) VALUES (?)", actorId);
        permanentlyLockTarget();
        var session = authenticatedSession();
        enableUnlockAuditFailure();
        try {
            mvc.perform(post(endpoint()).session(session).with(csrf())
                    .contentType("application/json")
                    .content("{\"reason\":\"incident reviewed\"}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .jsonPath("$.detail")
                            .value("An unexpected error occurred. Please try again later."));
            assertTrue(targetRequiresAdministratorUnlock(),
                    "Credential unlock must roll back when mandatory audit insert fails");
            assertEquals(0, eventCount("ACCOUNT_UNLOCK"),
                    "Failed audit must not leave a successful unlock event");
        } finally {
            disableUnlockAuditFailure();
        }
        mvc.perform(post(endpoint()).session(session).with(csrf())
                .contentType("application/json")
                .content("{\"reason\":\"incident reviewed\"}"))
                .andExpect(status().isNoContent());
        assertFalse(targetRequiresAdministratorUnlock(),
                "Successful retry must clear administrator lockout");
        assertEquals(1, eventCount("ACCOUNT_UNLOCK"));
    }

    @Test void rejectedUnlockAuditOutageFailsClosedWithoutPersistingRejection() throws Exception {
        fixture();
        permanentlyLockTarget();
        var session = authenticatedSession();
        enableUnlockAuditFailure();
        try {
            mvc.perform(post(endpoint()).session(session).with(csrf())
                    .contentType("application/json")
                    .content("{\"reason\":\"incident reviewed\"}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .jsonPath("$.detail")
                            .value("An unexpected error occurred. Please try again later."));
            assertTrue(targetRequiresAdministratorUnlock());
            assertEquals(0, eventCount("ACCOUNT_UNLOCK_REJECTED"));
        } finally {
            disableUnlockAuditFailure();
        }
        mvc.perform(post(endpoint()).session(session).with(csrf())
                .contentType("application/json")
                .content("{\"reason\":\"incident reviewed\"}"))
                .andExpect(status().isForbidden());
        assertTrue(targetRequiresAdministratorUnlock());
        assertEquals(1, eventCount("ACCOUNT_UNLOCK_REJECTED"));
    }

    // B18HI-3/4-D: session lifecycle HTTP acceptance
    @Test void validLoginSessionCanAccessProtectedIdentityEndpoint() throws Exception {
        fixture();
        var session = authenticatedSession();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.userId").value(actorId.toString()));
    }

    @Test void changedCredentialVersionInvalidatesExistingAuthenticatedSession() throws Exception {
        fixture();
        var oldSession = authenticatedSession();
        jdbc.update("UPDATE user_credentials SET password_changed_at = password_changed_at "
                + "+ interval '2 seconds' WHERE user_id = ?", actorId);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/auth/me").session(oldSession))
                .andExpect(status().isUnauthorized());
    }

    @Test void passwordChangeEndpointInvalidatesSessionAndNewPasswordCanLogIn() throws Exception {
        fixture();
        var oldSession = authenticatedSession();
        String replacement = "Replacement-Test-Only-Password-456!";
        mvc.perform(post("/api/auth/change-password").session(oldSession).with(csrf())
                .contentType("application/json")
                .content("{\"currentPassword\":\"" + ACTOR_PASSWORD
                        + "\",\"newPassword\":\"" + replacement + "\"}"))
                .andExpect(status().isNoContent());
        assertTrue(oldSession.isInvalid(), "Password change must invalidate the current session");
        mvc.perform(post("/api/auth/login").with(csrf())
                .param("username", actorEmail).param("password", replacement))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").with(csrf())
                .param("username", actorEmail).param("password", ACTOR_PASSWORD))
                .andExpect(status().isUnauthorized());
    }

    @Test void deactivatedAccountLosesExistingSessionAccess() throws Exception {
        fixture();
        var session = authenticatedSession();
        jdbc.update("UPDATE users SET is_active = false WHERE user_id = ?", actorId);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/auth/me").session(session))
                .andExpect(status().isUnauthorized());
        assertTrue(session.isInvalid(), "Account deactivation must invalidate its session");
        mvc.perform(post("/api/auth/login").with(csrf())
                .param("username", actorEmail).param("password", ACTOR_PASSWORD))
                .andExpect(status().isUnauthorized());
    }

    @Test void logoutInvalidatesAuthenticatedSession() throws Exception {
        fixture();
        var session = authenticatedSession();
        mvc.perform(post("/api/auth/logout").session(session).with(csrf()))
                .andExpect(status().isNoContent());
        assertTrue(session.isInvalid(), "Logout must invalidate the session");
    }

    @Test void missingSessionIsUnauthorizedAndSessionWithoutCredentialVersionIsRejected() throws Exception {
        fixture();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
        var session = authenticatedSession();
        session.removeAttribute("EPMS_PASSWORD_CHANGED_AT");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/auth/me").session(session))
                .andExpect(status().isUnauthorized());
    }


    /** B18HI-5B-5D: a 429 is a network-source rejection, not a credential failure. */
    @Test void rateLimitedLoginDoesNotChangeCredentialLockoutState() throws Exception {
        fixture();
        String remote = "2001:db8:" + UUID.randomUUID().toString().substring(0, 4)
                + ":" + UUID.randomUUID().toString().substring(0, 4) + "::31";
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setRemoteAddr(remote);
        String source = rateLimiter.sourceDigest(request);
        try {
            mvc.perform(post("/api/auth/login").with(csrf())
                    .with(r -> { r.setRemoteAddr(remote); r.setServletPath("/api/auth/login"); return r; })
                    .param("username", actorEmail).param("password", "incorrect-password"))
                    .andExpect(status().isUnauthorized());
            var before = jdbc.queryForMap("""
                    SELECT failed_attempt_count, login_stage_failures, login_lockout_stage,
                           login_locked_until, login_admin_unlock_required, last_failed_attempt_at
                    FROM user_credentials WHERE user_id = ?
                    """, actorId);
            assertEquals(1, jdbc.update("""
                    UPDATE authentication_rate_limit_counters
                    SET minute_count = 20, hour_count = 20,
                        minute_window_start = date_trunc('minute', clock_timestamp()),
                        hour_window_start = date_trunc('hour', clock_timestamp()), blocked_until = NULL
                    WHERE source_key = ? AND policy_code = 'LOGIN'
                    """, source));
            for (int attempt = 0; attempt < 2; attempt++) {
                mvc.perform(post("/api/auth/login").with(csrf())
                        .with(r -> { r.setRemoteAddr(remote); r.setServletPath("/api/auth/login"); return r; })
                        .param("username", actorEmail).param("password", "incorrect-password"))
                        .andExpect(status().isTooManyRequests());
            }
            var after = jdbc.queryForMap("""
                    SELECT failed_attempt_count, login_stage_failures, login_lockout_stage,
                           login_locked_until, login_admin_unlock_required, last_failed_attempt_at
                    FROM user_credentials WHERE user_id = ?
                    """, actorId);
            assertEquals(before, after, "HTTP 429 must not modify credential failure or lockout state");
        } finally {
            jdbc.update("DELETE FROM authentication_security_events WHERE source_ip_hmac = ?", source);
            jdbc.update("DELETE FROM authentication_rate_limit_counters WHERE source_key = ?", source);
        }
    }

    // B18HI-3/4-E: expired session identifier cannot restore authentication
    @Test void expiredSessionIdentifierCannotRestoreAuthentication() throws Exception {
        fixture();
        var expired = authenticatedSession();
        String oldSessionId = expired.getId();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/auth/me").session(expired))
                .andExpect(status().isOk());

        // The servlet container expires a session by invalidating it. MockMvc does not
        // run the servlet container's idle-timeout scheduler, so simulate that boundary.
        expired.invalidate();
        assertTrue(expired.isInvalid(), "The old HTTP session must be invalidated");

        // A stale JSESSIONID cookie must not be treated as an authenticated session.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/auth/me")
                .cookie(new jakarta.servlet.http.Cookie("JSESSIONID", oldSessionId)))
                .andExpect(status().isUnauthorized());
    }


    /** B2D-4B Stage 3: a real administrator unlock serializes with SQL token issuance. */
    @Test void javaAdministratorUnlockSerializesWithIssuance() throws Exception {
        fixture();
        jdbc.update("INSERT INTO account_unlock_grants(user_id) VALUES (?)", actorId);
        permanentlyLockTarget();
        var released = new java.util.concurrent.CountDownLatch(1);
        var proceed = new java.util.concurrent.CountDownLatch(1);
        byte[] access = new byte[32];
        byte[] renewal = new byte[32];
        new java.security.SecureRandom().nextBytes(access);
        new java.security.SecureRandom().nextBytes(renewal);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var unlock = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        actorEmail, "not-used", java.util.List.of());
                boolean accepted = accountUnlockService.unlock(auth, targetId, "incident reviewed",
                        new org.springframework.mock.web.MockHttpServletRequest());
                released.countDown();
                try {
                    if (!proceed.await(20, java.util.concurrent.TimeUnit.SECONDS))
                        throw new IllegalStateException("Unlock release timed out");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
                return accepted;
            }));
            try {
                assertTrue(released.await(20, java.util.concurrent.TimeUnit.SECONDS));
                var issuance = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                    java.util.UUID session = jdbc.queryForObject("SELECT public.epms_auth_issue(?,?,?)",
                            java.util.UUID.class, targetId, access, renewal);
                    status.setRollbackOnly();
                    return session;
                }));
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                boolean waited = false;
                while (System.nanoTime() < deadline) {
                    Integer count = jdbc.queryForObject(
                            "SELECT count(*) FROM pg_catalog.pg_stat_activity WHERE datname=current_database() " +
                            "AND wait_event_type='Lock' AND wait_event='advisory'", Integer.class);
                    if (count != null && count > 0) { waited = true; break; }
                    Thread.sleep(25);
                }
                assertTrue(waited, "Issuance must wait for the Java unlock transaction");
                proceed.countDown();
                assertTrue(unlock.get(20, java.util.concurrent.TimeUnit.SECONDS));
                assertNotNull(issuance.get(20, java.util.concurrent.TimeUnit.SECONDS));
                assertFalse(targetRequiresAdministratorUnlock());
            } finally {
                proceed.countDown();
                unlock.get(20, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
    }

}
