package com.jamesstevens.epms.security.rbac;

import com.jamesstevens.epms.security.service.PasswordScreeningDecisionService;
import com.jamesstevens.epms.security.service.ProvisionalCredentialIssuer;
import com.jamesstevens.epms.security.service.AuthenticatedSessionValidationService;
import com.jamesstevens.epms.user.entity.User;
import com.jamesstevens.epms.user.entity.UserCredential;
import com.jamesstevens.epms.user.repository.UserRepository;
import com.jamesstevens.epms.user.repository.UserCredentialRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.mock.web.MockHttpSession;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real filter chain with authenticated test principals and a mocked administration boundary. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RbacAuthenticatedHttpIntegrationTest {
    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18")
            .withInitScript("rbac-test-db-init.sql");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("epms.rbac.enabled", () -> "true");
        registry.add("epms.rbac.jdbc-url", postgres::getJdbcUrl);
        registry.add("epms.rbac.username", () -> "epms_rbac_runtime");
        registry.add("epms.rbac.password", () -> "rbac-test-only-local-container-password");
        registry.add("epms.security.audit.hmac-key", () -> "test-only-http-rbac-hmac-key-123456789");
    }

    @Autowired MockMvc mvc;
    @MockitoBean RbacAdministrationService administration;
    @MockitoBean PasswordScreeningDecisionService screening;
    @MockitoBean ProvisionalCredentialIssuer provisional;
    @MockitoBean AuthenticatedSessionValidationService sessionValidation;
    @MockitoBean UserRepository userRepository;
    @MockitoBean UserCredentialRepository credentialRepository;
    private static final String ACTOR_EMAIL = "admin@example.test";
    private static final OffsetDateTime PASSWORD_CHANGED = OffsetDateTime.parse("2026-11-01T12:00:00Z");

    @BeforeEach
    void validAuthenticatedSession() {
        User actor = mock(User.class);
        UserCredential credential = mock(UserCredential.class);
        UUID actorId = UUID.fromString("a78c6b6a-169b-4e21-9691-f9973e3ec4d1");
        when(sessionValidation.isEligible(ACTOR_EMAIL)).thenReturn(true);
        when(userRepository.findByEmailIgnoreCase(ACTOR_EMAIL)).thenReturn(Optional.of(actor));
        when(actor.getUserId()).thenReturn(actorId);
        when(credentialRepository.findByUserId(actorId)).thenReturn(Optional.of(credential));
        when(credential.getPasswordChangedAt()).thenReturn(PASSWORD_CHANGED);
        when(credential.getScreeningStatus()).thenReturn("VERIFIED");
        when(credential.isMustChangePassword()).thenReturn(false);
    }

    private static MockHttpSession authenticatedSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("EPMS_PASSWORD_CHANGED_AT", PASSWORD_CHANGED.toInstant().toEpochMilli());
        long now = java.time.Instant.now().toEpochMilli();
        session.setAttribute("EPMS_SESSION_STARTED_AT", now);
        session.setAttribute("EPMS_AUTHENTICATED_AT", now);
        session.setAttribute("EPMS_LAST_ACTIVITY_AT", now);
        return session;
    }


    @Test void authenticatedAssignmentWithCsrfReachesController() throws Exception {
        UUID target = UUID.randomUUID();
        UUID assignment = UUID.randomUUID();
        when(administration.assign(any(), eq(target), eq("PROJECT_MANAGER"), eq("approved")))
                .thenReturn(assignment);
        mvc.perform(post("/api/security/role-assignments")
                .session(authenticatedSession()).with(user(ACTOR_EMAIL)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetUserId\":\"" + target + "\",\"roleCode\":\"PROJECT_MANAGER\",\"reason\":\"approved\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignmentId").value(assignment.toString()));
        verify(administration).assign(any(), eq(target), eq("PROJECT_MANAGER"), eq("approved"));
    }

    @Test void authenticatedAssignmentWithoutCsrfIsForbidden() throws Exception {
        mvc.perform(post("/api/security/role-assignments")
                .session(authenticatedSession()).with(user(ACTOR_EMAIL))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetUserId\":\"" + UUID.randomUUID() + "\",\"roleCode\":\"PROJECT_MANAGER\",\"reason\":\"approved\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(administration);
    }

    @Test void authenticatedRevocationWithCsrfReachesController() throws Exception {
        UUID assignment = UUID.randomUUID();
        UUID result = UUID.randomUUID();
        when(administration.revoke(any(), eq(assignment), eq("approved"))).thenReturn(result);
        mvc.perform(post("/api/security/role-assignments/{id}/revoke", assignment)
                .session(authenticatedSession()).with(user(ACTOR_EMAIL)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"approved\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignmentId").value(result.toString()));
        verify(administration).revoke(any(), eq(assignment), eq("approved"));
    }
}
