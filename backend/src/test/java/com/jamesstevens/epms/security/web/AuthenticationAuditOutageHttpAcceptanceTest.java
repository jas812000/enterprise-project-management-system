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

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exercises audit database write failures through the real Spring Security HTTP filter chain. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = "epms.security.audit.hmac-key=acceptance-test-only-key-not-for-production-12345")
class AuthenticationAuditOutageHttpAcceptanceTest {
    private static final UUID HUMAN = UUID.fromString("0199a000-0000-7000-8000-000000000001");
    private static final String PASSWORD = "Test-Only-Auth-Password-123!";
    private static final String TRIGGER_NAME = "epms_test_auth_audit_outage";
    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18")
            .withInitScript("test-db-init.sql");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrganizationService organizations;
    @Autowired UserService users;
    @Autowired UserCredentialRepository credentials;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean PasswordScreeningDecisionService screening;
    @MockitoBean ProvisionalCredentialIssuer provisional;

    UUID organizationId;
    UUID userId;
    String email;

    private void createUser() {
        when(screening.decide(anyString()))
                .thenReturn(PasswordScreeningDecisionService.Decision.VERIFIED);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            organizationId = organizations.createOrganization(
                    new CreateOrganizationRequest("EPMS Audit Outage Acceptance",
                            "Audit transaction failure verification")).organizationId();
            email = "outage-" + UUID.randomUUID() + "@example.com";
            userId = users.createUser(new CreateUserRequest(null, null, HUMAN,
                    "AUD-" + UUID.randomUUID().toString().substring(0, 12),
                    "Security", "Outage", email)).userId();
            credentials.saveAndFlush(new UserCredential(userId,
                    passwordEncoder.encode(PASSWORD), "ARGON2ID", false));
        });
    }

    private void enableAuditFailure() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION epms_test_reject_auth_audit()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  RAISE EXCEPTION 'simulated audit persistence outage';
                END;
                $$
                """);
        jdbc.execute("CREATE TRIGGER " + TRIGGER_NAME
                + " BEFORE INSERT ON authentication_security_events "
                + "FOR EACH ROW EXECUTE FUNCTION epms_test_reject_auth_audit()");
    }

    private void disableAuditFailure() {
        jdbc.execute("DROP TRIGGER IF EXISTS " + TRIGGER_NAME
                + " ON authentication_security_events");
        jdbc.execute("DROP FUNCTION IF EXISTS epms_test_reject_auth_audit()");
    }

    private int countEvents() {
        return jdbc.queryForObject("SELECT count(*) FROM authentication_security_events "
                + "WHERE target_user_id = ?", Integer.class, userId);
    }

    private int stageFailures() {
        return jdbc.queryForObject("SELECT login_stage_failures FROM user_credentials "
                + "WHERE user_id = ?", Integer.class, userId);
    }

    @AfterEach
    void cleanup() {
        disableAuditFailure();
        if (userId == null) return;
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("DELETE FROM authentication_security_events WHERE target_user_id = ?", userId);
            jdbc.update("DELETE FROM user_credentials WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM users WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM organizations WHERE organization_id = ?", organizationId);
        });
    }

    @Test
    void rejectedKnownAccountLoginFailsClosedAndRollsBackFailureCount() throws Exception {
        createUser();
        int before = stageFailures();
        enableAuditFailure();
        mvc.perform(post("/api/auth/login").with(csrf())
                .param("username", email).param("password", "incorrect-password"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value("Authentication is temporarily unavailable."));
        assertEquals(before, stageFailures(), "Failed audit must roll back lockout mutation");
        assertEquals(0, countEvents(), "Failed audit must not leave a partial event");
    }

    @Test
    void successfulCredentialLoginFailsClosedWithoutEstablishingSession() throws Exception {
        createUser();
        enableAuditFailure();
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                .param("username", email).param("password", PASSWORD))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value("Authentication is temporarily unavailable."))
                .andReturn();
        assertEquals(0, countEvents());
        assertEquals(0, stageFailures());
        var session = result.getRequest().getSession(false);
        if (session != null) {
            assertNull(session.getAttribute("SPRING_SECURITY_CONTEXT"),
                    "Audit failure must not establish authenticated session");
        }
    }

    @Test
    void unknownAccountLoginFailsClosedAndDoesNotExposeDatabaseDetails() throws Exception {
        enableAuditFailure();
        mvc.perform(post("/api/auth/login").with(csrf())
                .param("username", "unknown-" + UUID.randomUUID() + "@example.com")
                .param("password", "incorrect-password"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value("Authentication is temporarily unavailable."))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("simulated audit persistence outage"))));
    }
}
