package com.jamesstevens.epms.team;

import com.jamesstevens.epms.team.service.TeamProfileService;
import com.jamesstevens.epms.team.service.TeamNotificationService;
import com.jamesstevens.epms.team.dto.TeamNotification;
import java.util.List;
import com.jamesstevens.epms.team.service.TeamOperationsService;
import com.jamesstevens.epms.team.service.TeamAccountLifecycleService;
import com.jamesstevens.epms.team.dto.TeamProfileChange;
import com.jamesstevens.epms.common.exception.ResourceNotFoundException;
import com.jamesstevens.epms.common.exception.ResourceConflictException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

/** Real filter chain with authenticated test principals and a mocked Team service boundary. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class TeamHttpIntegrationTest {
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
    @MockitoBean TeamProfileService profiles;
    @MockitoBean TeamNotificationService notifications;
    @MockitoBean TeamOperationsService operations;
    @MockitoBean TeamAccountLifecycleService lifecycle;
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


    private static final String TEAMS = "/api/teams";
    private static final String PROFILE = "{\"name\":\"Platform\",\"reason\":\"Create\"}";

    @Test
    void anonymousReadIsUnauthorized() throws Exception {
        mvc.perform(get(TEAMS)).andExpect(status().isUnauthorized());
        verifyNoInteractions(profiles);
    }

    @Test
    void authenticatedMutationRequiresCsrf() throws Exception {
        mvc.perform(post(TEAMS).session(authenticatedSession()).with(user(ACTOR_EMAIL))
                .contentType(MediaType.APPLICATION_JSON).content(PROFILE)).andExpect(status().isForbidden());
        verifyNoInteractions(profiles);
    }

    @Test
    void createReturnsIdentifierAndLocation() throws Exception {
        UUID team = UUID.randomUUID();
        TeamProfileChange change = new TeamProfileChange("Platform", null, false, "Create");
        when(profiles.create(change)).thenReturn(team);
        mvc.perform(post(TEAMS).session(authenticatedSession()).with(user(ACTOR_EMAIL)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(PROFILE))
                .andExpect(status().isCreated()).andExpect(header().string("Location", TEAMS + "/" + team))
                .andExpect(jsonPath("$.teamId").value(team.toString()));
        verify(profiles).create(change);
    }

    @Test
    void invalidProfileNeverReachesService() throws Exception {
        mvc.perform(post(TEAMS).session(authenticatedSession()).with(user(ACTOR_EMAIL)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" \"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(profiles);
    }

    @Test
    void inaccessibleProfileReturnsSanitizedNotFound() throws Exception {
        UUID team = UUID.randomUUID();
        when(profiles.get(team)).thenThrow(new ResourceNotFoundException("private detail"));
        mvc.perform(get(TEAMS + "/{teamId}", team).session(authenticatedSession()).with(user(ACTOR_EMAIL)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").value("Team unavailable."));
    }

    @Test
    void deniedAndConflictingCommandsAreSanitized() throws Exception {
        UUID team = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        when(operations.joinMember(team, target, "Join")).thenThrow(new AccessDeniedException("private detail"));
        mvc.perform(post(TEAMS + "/{teamId}/memberships", team).session(authenticatedSession())
                .with(user(ACTOR_EMAIL)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + target + "\",\"reason\":\"Join\"}"))
                .andExpect(status().isForbidden());
        when(operations.transition(team, "ACTIVE", "Activate")).thenThrow(new ResourceConflictException("private"));
        mvc.perform(post(TEAMS + "/{teamId}/lifecycle", team).session(authenticatedSession())
                .with(user(ACTOR_EMAIL)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"ACTIVE\",\"reason\":\"Activate\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void accountLifecycleUsesProtectedService() throws Exception {
        UUID target = UUID.randomUUID();
        UUID result = UUID.randomUUID();
        when(lifecycle.disableAccount(target, "PLANNED_DEACTIVATION", "Approved")).thenReturn(result);
        mvc.perform(post("/api/security/users/{userId}/lifecycle", target).session(authenticatedSession())
                .with(user(ACTOR_EMAIL)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"PLANNED_DEACTIVATION\",\"reason\":\"Approved\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resultId").value(result.toString()));
        verify(lifecycle).disableAccount(target, "PLANNED_DEACTIVATION", "Approved");
    }

    @Test
    void databaseFailureDoesNotRevealDetails() throws Exception {
        when(profiles.list(50, 0)).thenThrow(new DataAccessResourceFailureException("private database detail"));
        mvc.perform(get(TEAMS).session(authenticatedSession()).with(user(ACTOR_EMAIL)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Team operation unavailable."));
    }

    @Test
    void dispatchFailureDiagnosticsRequireAuthenticationAndAuthorization() throws Exception {
        mvc.perform(get("/api/team-notifications/dispatch-failures")).andExpect(status().isUnauthorized());
        verifyNoInteractions(notifications);
        when(notifications.failures(50, 0)).thenThrow(new AccessDeniedException("private detail"));
        mvc.perform(get("/api/team-notifications/dispatch-failures").session(authenticatedSession())
                .with(user(ACTOR_EMAIL))).andExpect(status().isForbidden());
        verify(notifications).failures(50, 0);
    }

    @Test
    void notificationInboxRequiresAuthenticationAndUsesRecipientBoundary() throws Exception {
        mvc.perform(get("/api/team-notifications")).andExpect(status().isUnauthorized());
        verifyNoInteractions(notifications);
        UUID notice = UUID.randomUUID();
        UUID team = UUID.randomUUID();
        when(notifications.inbox(50, 0)).thenReturn(List.of(new TeamNotification(notice, team,
                "LEADERSHIP_CHANGED", "INFO", PASSWORD_CHANGED, null, null)));
        mvc.perform(get("/api/team-notifications").session(authenticatedSession()).with(user(ACTOR_EMAIL)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].notificationId").value(notice.toString()))
                .andExpect(jsonPath("$[0].reason").doesNotExist());
        verify(notifications).inbox(50, 0);
    }

    @Test
    void notificationReceiptRequiresCsrfAndRoutesAuthenticatedRequest() throws Exception {
        UUID notice = UUID.randomUUID();
        String path = "/api/team-notifications/{notificationId}/acknowledge";
        mvc.perform(post(path, notice).session(authenticatedSession()).with(user(ACTOR_EMAIL)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(notifications);
        mvc.perform(post(path, notice).session(authenticatedSession()).with(user(ACTOR_EMAIL)).with(csrf()))
                .andExpect(status().isNoContent());
        verify(notifications).acknowledge(notice);
    }

    @Test
    void administrativeNotificationDispatchDenialIsSanitized() throws Exception {
        when(notifications.dispatch(50)).thenThrow(new AccessDeniedException("private detail"));
        mvc.perform(post("/api/team-notifications/dispatch").session(authenticatedSession())
                .with(user(ACTOR_EMAIL)).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Access to this resource is denied."));
        verify(notifications).dispatch(50);
    }
}
