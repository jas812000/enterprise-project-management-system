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
class B8LiveRbacHttpIntegrationTest {
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
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("dataSource") javax.sql.DataSource applicationDataSource;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("rbacJdbcTemplate") org.springframework.jdbc.core.JdbcTemplate restrictedRbac;
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



    /** The browser session survives a role change; database authority must not. */
    @Test
    void sameHttpSessionRechecksDatabaseRoleAfterRevocation() throws Exception {
        org.springframework.jdbc.core.JdbcTemplate setup =
                new org.springframework.jdbc.core.JdbcTemplate(applicationDataSource);
        var organizations = setup.queryForList(
                "SELECT organization_id FROM public.organizations LIMIT 1", UUID.class);
        UUID organization = organizations.isEmpty() ? null : organizations.getFirst();
        if (organization == null) {
            organization = setup.queryForObject(
                    "INSERT INTO public.organizations(name) VALUES (?) RETURNING organization_id",
                    UUID.class, "HTTP RBAC acceptance organization");
        }
        org.junit.jupiter.api.Assertions.assertNotNull(organization);
        UUID actor = createRbacUser(setup, organization, "http-actor");
        UUID secondAdmin = createRbacUser(setup, organization, "http-second-admin");
        UUID firstTarget = createRbacUser(setup, organization, "http-first-target");
        UUID deniedTarget = createRbacUser(setup, organization, "http-denied-target");
        setup.update("""
                INSERT INTO public.security_user_roles(user_id, role_id, granted_by_user_id)
                SELECT ?, role_id, ? FROM public.security_roles WHERE code='SYSTEM_ADMIN'
                """, actor, actor);
        setup.update("""
                INSERT INTO public.security_user_roles(user_id, role_id, granted_by_user_id)
                SELECT ?, role_id, ? FROM public.security_roles WHERE code='SYSTEM_ADMIN'
                """, secondAdmin, actor);
        UUID actorAssignment = setup.queryForObject("""
                SELECT a.assignment_id FROM public.security_user_roles a
                JOIN public.security_roles r ON r.role_id=a.role_id
                WHERE a.user_id=? AND r.code='SYSTEM_ADMIN' AND a.revoked_at IS NULL
                """, UUID.class, actor);
        User actorRecord = mock(User.class);
        when(actorRecord.getUserId()).thenReturn(actor);
        when(actorRecord.isActive()).thenReturn(true);
        when(actorRecord.isArchived()).thenReturn(false);
        when(userRepository.findByEmailIgnoreCase(ACTOR_EMAIL)).thenReturn(Optional.of(actorRecord));
        // Session validation resolves credentials using the database-backed actor UUID.
        // The @BeforeEach stub uses a fixed UUID for the other HTTP fixture cases.
        UserCredential actorCredential = mock(UserCredential.class);
        when(actorCredential.getPasswordChangedAt()).thenReturn(PASSWORD_CHANGED);
        when(actorCredential.getScreeningStatus()).thenReturn("VERIFIED");
        when(actorCredential.isMustChangePassword()).thenReturn(false);
        when(credentialRepository.findByUserId(actor)).thenReturn(Optional.of(actorCredential));

        MockHttpSession session = authenticatedSession();
        mvc.perform(post("/api/security/role-assignments")
                .session(session).with(user(ACTOR_EMAIL)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetUserId\":\"" + firstTarget
                        + "\",\"roleCode\":\"VIEWER\",\"reason\":\"before revocation\"}"))
                .andExpect(status().isOk());

        restrictedRbac.queryForObject("SELECT public.epms_rbac_revoke_role(?,?,?)",
                UUID.class, secondAdmin, actorAssignment, "revoke HTTP actor for acceptance test");
        int before = setup.queryForObject(
                "SELECT count(*) FROM public.security_role_assignment_history WHERE target_user_id=?",
                Integer.class, deniedTarget);
        try {
            var denied = mvc.perform(post("/api/security/role-assignments")
                    .session(session).with(user(ACTOR_EMAIL)).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"targetUserId\":\"" + deniedTarget
                            + "\",\"roleCode\":\"VIEWER\",\"reason\":\"after revocation\"}"))
                    .andReturn();
            org.junit.jupiter.api.Assertions.assertTrue(denied.getResponse().getStatus() >= 400,
                    "Revoked administrator must not obtain HTTP success");
        } catch (jakarta.servlet.ServletException deniedByDatabase) {
            org.junit.jupiter.api.Assertions.assertNotNull(deniedByDatabase.getCause(),
                    "Denied request must be rejected by the underlying service");
        }
        org.junit.jupiter.api.Assertions.assertEquals(0, setup.queryForObject("""
                SELECT count(*) FROM public.security_user_roles WHERE user_id=? AND revoked_at IS NULL
                """, Integer.class, deniedTarget));
        org.junit.jupiter.api.Assertions.assertEquals(before, setup.queryForObject(
                "SELECT count(*) FROM public.security_role_assignment_history WHERE target_user_id=?",
                Integer.class, deniedTarget));
    }

    private static UUID createRbacUser(org.springframework.jdbc.core.JdbcTemplate jdbc,
                                       UUID organization, String prefix) {
        return jdbc.queryForObject("""
                INSERT INTO public.users(organization_id, account_type_id, first_name, last_name, email)
                SELECT ?, account_type_id, ?, 'RBAC', ? FROM public.account_types WHERE code='HUMAN'
                RETURNING user_id
                """, UUID.class, organization, prefix,
                prefix + UUID.randomUUID() + "@example.invalid");
    }

}
