package com.jamesstevens.epms.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import com.jamesstevens.epms.organization.dto.CreateOrganizationRequest;
import com.jamesstevens.epms.organization.service.OrganizationService;
import com.jamesstevens.epms.security.service.PasswordScreeningDecisionService;
import com.jamesstevens.epms.security.service.ProvisionalCredentialIssuer;
import com.jamesstevens.epms.user.dto.CreateUserRequest;
import com.jamesstevens.epms.user.entity.UserCredential;
import com.jamesstevens.epms.user.repository.UserCredentialRepository;
import com.jamesstevens.epms.user.service.UserService;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real login, HTTP filters, services and restricted Team commands in disposable PostgreSQL. */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@Testcontainers
class TeamAuthenticatedHttpDatabaseIntegrationTest {
    private static final UUID HUMAN = UUID.fromString("0199a000-0000-7000-8000-000000000001");
    private static final String PASSWORD = "Test-Only-Team-Password-123!";
    private static final String TEAMS = "/api/teams";
    private static final String NOTIFICATIONS = "/api/team-notifications";
    private static final String REASON = "{\"reason\":\"Reviewed\"}";
    private static final String PROFILE = "{\"name\":\"Platform\",\"reason\":\"Create\"}";
    private static final String EMERGENCY_BODY = """
            {"type":"EMERGENCY_UNAVAILABILITY","reason":"Emergency review"}
            """;
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
        registry.add("epms.security.audit.hmac-key", () -> "test-only-team-http-audit-key-123456789");
        registry.add("epms.team.notifications.automatic.enabled", () -> "false");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate fixtures;
    @Autowired @Qualifier("rbacJdbcTemplate") JdbcTemplate restricted;
    @Autowired OrganizationService organizations;
    @Autowired UserService users;
    @Autowired UserCredentialRepository credentials;
    @Autowired PasswordEncoder encoder;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean PasswordScreeningDecisionService screening;
    @MockitoBean ProvisionalCredentialIssuer provisional;

    private UUID createActor(String role) {
        return createActor(role, email(role));
    }

    private UUID createActor(String role, String address) {
        return new TransactionTemplate(transactions).execute(status -> {
            UUID id = users.createUser(new CreateUserRequest(null, null, HUMAN,
                    "HTTP-" + UUID.randomUUID().toString().substring(0, 12),
                    "Team", "Acceptance", address)).userId();
            credentials.saveAndFlush(new UserCredential(id, encoder.encode(PASSWORD), "ARGON2ID", false));
            fixtures.update("""
                    INSERT INTO public.security_user_roles(user_id,role_id)
                    SELECT ?,role_id FROM public.security_roles WHERE code=?
                    """, id, role);
            return id;
        });
    }

    private static String email(String role) {
        return role.toLowerCase(java.util.Locale.ROOT) + "@example.invalid";
    }

    private MockHttpSession login(String role) throws Exception {
        return loginAddress(email(role));
    }

    private MockHttpSession loginAddress(String address) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                .param("username", address).param("password", PASSWORD))
                .andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session);
        assertNotNull(session.getAttribute("EPMS_PASSWORD_CHANGED_AT"));
        return session;
    }

    @Test
    void realLoginAndProtectedTeamOperations(CapturedOutput output) throws Exception {
        when(screening.decide(anyString())).thenReturn(PasswordScreeningDecisionService.Decision.VERIFIED);
        organizations.createOrganization(new CreateOrganizationRequest("HTTP Team acceptance", "Test only"));
        createActor("SYSTEM_ADMIN");
        UUID member = createActor("MEMBER");
        assertEquals("epms_rbac_runtime", restricted.queryForObject("SELECT current_user", String.class));
        MockHttpSession admin = login("SYSTEM_ADMIN");
        UUID team = verifyProfile(admin);
        verifyMembership(admin, team, member);
        verifyRelationshipErrors(admin, team);
        verifyDeferredScopes(team);
        verifyConfidential(admin, team);
        verifyAuditHistoryHttp(admin, team, output);
        verifyNotificationReceipts(admin, team, member);
        verifySessionExpiry();
        verifyAccountRevocationAndAlerts(admin, team, member);
        verifyRemainingLeadershipCommands(admin);
    }

    private UUID verifyProfile(MockHttpSession admin) throws Exception {
        mvc.perform(get(TEAMS)).andExpect(status().isUnauthorized());
        mvc.perform(post(TEAMS).session(admin).contentType(MediaType.APPLICATION_JSON).content(PROFILE))
                .andExpect(status().isForbidden());
        mvc.perform(post(TEAMS).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(TEAMS).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(PROFILE))
                .andExpect(status().isCreated());
        UUID team = fixtures.queryForObject("SELECT team_id FROM public.teams WHERE name='Platform'", UUID.class);
        assertNotNull(team);
        verifyUnknownFields(admin, team);
        verifyProfileUpdate(admin, team);
        mvc.perform(get(TEAMS + "/" + team).session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Platform"));
        mvc.perform(put(TEAMS + "/" + team).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":999,\"profile\":" + PROFILE + "}" )).andExpect(status().isConflict());
        assertEquals("Platform", fixtures.queryForObject("SELECT name FROM public.teams WHERE team_id=?",
                String.class, team));
        return team;
    }

    private void verifyProfileUpdate(MockHttpSession admin, UUID team) throws Exception {
        Long version = fixtures.queryForObject("SELECT version FROM public.teams WHERE team_id=?",
                Long.class, team);
        String profile = "{\"name\":\"Platform\",\"description\":\"Reviewed\",\"reason\":\"Update\"}";
        mvc.perform(put(TEAMS + "/" + team).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + version + ",\"profile\":" + profile + "}"))
                .andExpect(status().isOk());
        assertEquals("Reviewed", fixtures.queryForObject("SELECT description FROM public.teams WHERE team_id=?",
                String.class, team));
        mvc.perform(post(TEAMS + "/name-conflict-review").session(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"teamId\":\"" + team + "\",\"name\":\"Platform\"}"))
                .andExpect(status().isOk());
        mvc.perform(get(TEAMS + "/" + team + "/history").session(admin)).andExpect(status().isOk());
    }

    private void verifyRelationshipErrors(MockHttpSession admin, UUID team) throws Exception {
        String path = TEAMS + "/" + team;
        String missing = UUID.randomUUID().toString();
        mvc.perform(post(path + "/memberships/" + missing + "/end").session(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"endType":"ADMINISTRATIVE","reasonCode":"TEAM_REASSIGNMENT","comment":"Review"}
                        """)).andExpect(status().isConflict());
        mvc.perform(post(path + "/leadership/" + missing + "/end").session(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(REASON)).andExpect(status().isConflict());
        mvc.perform(post(path + "/alternates").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"membershipId\":\"" + missing + "\",\"priority\":1,\"reason\":\"Review\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post(path + "/alternates/" + missing + "/revoke").session(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(REASON)).andExpect(status().isConflict());
        assertEquals("ACTIVE", fixtures.queryForObject("SELECT lifecycle_status FROM public.teams WHERE team_id=?",
                String.class, team));
    }

    private void verifyMembership(MockHttpSession admin, UUID team, UUID member) throws Exception {
        String path = TEAMS + "/" + team;
        String join = "{\"userId\":\"" + member + "\",\"reason\":\"Join\"}";
        mvc.perform(post(path + "/memberships").session(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(join)).andExpect(status().isOk());
        UUID membership = fixtures.queryForObject("""
                SELECT team_membership_id FROM public.team_memberships WHERE team_id=? AND user_id=?
                """, UUID.class, team, member);
        String appointment = "{\"membershipId\":\"" + UUID.randomUUID() + "\",\"reason\":\"Appoint\"}";
        mvc.perform(post(path + "/leadership").session(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(appointment)).andExpect(status().isConflict());
        assertEquals(0, fixtures.queryForObject("SELECT count(*) FROM public.team_leadership_assignments",
                Integer.class));
        appointment = "{\"membershipId\":\"" + membership + "\",\"reason\":\"Appoint\"}";
        mvc.perform(post(path + "/leadership").session(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(appointment)).andExpect(status().isOk());
        mvc.perform(post(path + "/lifecycle").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"ACTIVE\",\"reason\":\"Activate\"}")).andExpect(status().isOk());
        assertEquals("ACTIVE", fixtures.queryForObject("SELECT lifecycle_status FROM public.teams WHERE team_id=?",
                String.class, team));
    }

    private void verifyDeferredScopes(UUID team) throws Exception {
        for (String role : List.of("PROJECT_MANAGER", "VIEWER", "AUDITOR")) {
            createActor(role);
            MockHttpSession session = login(role);
            mvc.perform(get(TEAMS).session(session)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
            mvc.perform(get(TEAMS + "/" + team).session(session)).andExpect(status().isNotFound());
            mvc.perform(get(TEAMS + "/" + UUID.randomUUID()).session(session)).andExpect(status().isNotFound());
            mvc.perform(post(TEAMS).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(PROFILE)).andExpect(status().isForbidden());
        }
    }

    private void verifyAuditHistoryHttp(MockHttpSession admin, UUID team, CapturedOutput output) throws Exception {
        String path = TEAMS + "/" + team + "/confidential-access-history";
        mvc.perform(get(path).session(admin)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].outcome").value("GRANTED"))
                .andExpect(jsonPath("$[0].justification").doesNotExist());
        mvc.perform(get(path).session(admin).param("limit", "0")).andExpect(status().isBadRequest());
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        assertTrue(output.getAll().contains("TEAM_CONFIDENTIAL_HTTP_REJECTED route=ACCESS_HISTORY status=401"));
        String event = TEAMS + "/" + team + "/history/" + UUID.randomUUID() + "/confidential";
        mvc.perform(post(event).session(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceKind\":\"OPERATION\",\"justification\":\"Sensitive rejected body\"}"))
                .andExpect(status().isForbidden());
        assertTrue(output.getAll().contains("TEAM_CONFIDENTIAL_HTTP_REJECTED route=EVENT status=403"));
        assertFalse(output.getAll().lines().filter(line -> line.contains("TEAM_CONFIDENTIAL_HTTP_REJECTED"))
                .anyMatch(line -> line.contains("Sensitive rejected body") || line.contains(team.toString())));
    }

    private void verifyConfidential(MockHttpSession admin, UUID team) throws Exception {
        UUID event = fixtures.queryForObject("""
                SELECT event_id FROM public.team_operation_events WHERE team_id=? AND operation='MEMBER_JOIN'
                """, UUID.class, team);
        String path = TEAMS + "/" + team + "/history/" + event + "/confidential";
        String review = "{\"sourceKind\":\"OPERATION\",\"justification\":\"Review\"}";
        mvc.perform(post(path).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(review))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        assertEquals(1, fixtures.queryForObject("""
                SELECT count(*) FROM public.team_confidential_audit_access WHERE outcome='GRANTED'
                """, Integer.class));
        mvc.perform(post(path).session(admin).contentType(MediaType.APPLICATION_JSON).content(review))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/team-notifications/alerts").session(admin)).andExpect(status().isOk());
        mvc.perform(get(TEAMS + "/" + team + "/alternate-readiness").session(admin)).andExpect(status().isOk());
    }

    private void verifyUnknownFields(MockHttpSession admin, UUID team) throws Exception {
        String unknown = "{\"name\":\"Unexpected\",\"reason\":\"Create\",\"actorUserId\":null}";
        mvc.perform(post(TEAMS).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(unknown)).andExpect(status().isBadRequest());
        Long version = fixtures.queryForObject("SELECT version FROM public.teams WHERE team_id=?",
                Long.class, team);
        String nested = "{\"version\":" + version + ",\"profile\":" + unknown + "}";
        mvc.perform(put(TEAMS + "/" + team).session(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(nested)).andExpect(status().isBadRequest());
        assertEquals(1, fixtures.queryForObject("SELECT count(*) FROM public.teams", Integer.class));
        assertEquals(version, fixtures.queryForObject("SELECT version FROM public.teams WHERE team_id=?",
                Long.class, team));
    }

    private void verifyNotificationReceipts(MockHttpSession admin, UUID team, UUID member) throws Exception {
        MockHttpSession session = login("MEMBER");
        mvc.perform(post(NOTIFICATIONS + "/dispatch").session(session).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post(NOTIFICATIONS + "/dispatch").session(admin).with(csrf()))
                .andExpect(status().isOk());
        mvc.perform(get(NOTIFICATIONS + "/dispatch-failures").session(admin)).andExpect(status().isOk());
        mvc.perform(get(NOTIFICATIONS + "/dispatch-failures").session(session)).andExpect(status().isForbidden());
        UUID notification = fixtures.queryForObject("""
                SELECT n.notification_id FROM public.team_notifications n
                JOIN public.team_notification_dispatches d USING(dispatch_id)
                JOIN public.team_operation_events e ON e.event_id=d.operation_event_id
                WHERE e.team_id=? AND e.operation='MEMBER_JOIN' AND n.recipient_user_id=?
                """, UUID.class, team, member);
        assertNotNull(notification);
        mvc.perform(get(NOTIFICATIONS).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.notificationId == '" + notification + "')]").isNotEmpty());
        verifyReceiptOwnership(admin, session, notification);
        verifyConfidentialDenial(session, team);
    }

    private void verifyReceiptOwnership(MockHttpSession admin, MockHttpSession member, UUID notification)
            throws Exception {
        String path = NOTIFICATIONS + "/" + notification;
        mvc.perform(post(path + "/read").session(admin).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post(path + "/read").session(member)).andExpect(status().isForbidden());
        assertEquals(0, receiptCount(notification));
        mvc.perform(post(path + "/read").session(member).with(csrf())).andExpect(status().isNoContent());
        assertEquals(1, receiptCount(notification));
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post(path + "/acknowledge").session(member).with(csrf()))
                    .andExpect(status().isNoContent());
        }
        assertEquals(2, receiptCount(notification));
    }

    private int receiptCount(UUID notification) {
        return java.util.Objects.requireNonNull(fixtures.queryForObject("""
                SELECT count(*) FROM public.team_notification_receipts WHERE notification_id=?
                """, Integer.class, notification));
    }

    private void verifyConfidentialDenial(MockHttpSession member, UUID team) throws Exception {
        UUID event = fixtures.queryForObject("""
                SELECT event_id FROM public.team_operation_events WHERE team_id=? AND operation='MEMBER_JOIN'
                """, UUID.class, team);
        String path = TEAMS + "/" + team + "/history/" + event + "/confidential";
        String body = "{\"sourceKind\":\"OPERATION\",\"justification\":\"Review\"}";
        mvc.perform(post(path).session(member).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        assertEquals(1, fixtures.queryForObject("""
                SELECT count(*) FROM public.team_confidential_audit_access
                WHERE requested_event_id=? AND outcome='DENIED' AND justification IS NULL
                """, Integer.class, event));
    }

    private void verifySessionExpiry() throws Exception {
        MockHttpSession expired = login("VIEWER");
        long old = Instant.now().minus(Duration.ofMinutes(31)).toEpochMilli();
        expired.setAttribute("EPMS_SESSION_STARTED_AT", old);
        expired.setAttribute("EPMS_AUTHENTICATED_AT", old);
        expired.setAttribute("EPMS_LAST_ACTIVITY_AT", old);
        mvc.perform(get(TEAMS).session(expired)).andExpect(status().isUnauthorized());
        assertTrue(expired.isInvalid());
        MockHttpSession stale = login("VIEWER");
        stale.setAttribute("EPMS_PASSWORD_CHANGED_AT", 0L);
        mvc.perform(get(NOTIFICATIONS).session(stale)).andExpect(status().isUnauthorized());
        assertTrue(stale.isInvalid());
    }

    private void verifyAccountRevocationAndAlerts(MockHttpSession admin, UUID team, UUID member)
            throws Exception {
        MockHttpSession session = login("MEMBER");
        String path = "/api/security/users/" + member + "/lifecycle";
        String body = "{\"type\":\"ACCOUNT_SECURITY\",\"reason\":\"Security review\"}";
        mvc.perform(post(path).session(login("VIEWER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post(path).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        assertFalse(Boolean.TRUE.equals(fixtures.queryForObject("SELECT is_active FROM public.users WHERE user_id=?",
                Boolean.class, member)));
        mvc.perform(get(TEAMS + "/" + team).session(session)).andExpect(status().isUnauthorized());
        assertTrue(session.isInvalid());
        assertEquals("INACTIVE", fixtures.queryForObject("SELECT lifecycle_status FROM public.teams WHERE team_id=?",
                String.class, team));
        assertEquals(0, fixtures.queryForObject("""
                SELECT count(*) FROM public.security_user_roles WHERE user_id=? AND revoked_at IS NULL
                """, Integer.class, member));
        verifyAlertResolution(admin, team);
    }

    private void verifyAlertResolution(MockHttpSession admin, UUID team) throws Exception {
        UUID alert = fixtures.queryForObject("""
                SELECT a.alert_id FROM public.team_security_alerts a
                JOIN public.team_operation_events e ON e.event_id=a.event_id WHERE e.team_id=?
                """, UUID.class, team);
        String path = NOTIFICATIONS + "/alerts/" + alert;
        mvc.perform(post(path + "/acknowledge").session(admin).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(post(path + "/resolve").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(REASON)).andExpect(status().isConflict());
        mvc.perform(get(NOTIFICATIONS + "/alerts").session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(post(TEAMS + "/" + team + "/lifecycle").session(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"ARCHIVED\",\"reason\":\"Close\"}"))
                .andExpect(status().isOk());
        mvc.perform(post(path + "/resolve").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(REASON)).andExpect(status().isNoContent());
        mvc.perform(get(NOTIFICATIONS + "/alerts").session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get(NOTIFICATIONS + "/alerts?includeResolved=true").session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        assertEquals(1, fixtures.queryForObject("""
                SELECT count(*) FROM public.team_security_alert_resolutions WHERE alert_id=?
                """, Integer.class, alert));
    }

    private void verifyRemainingLeadershipCommands(MockHttpSession admin) throws Exception {
        String body = "{\"name\":\"Research Operations\",\"reason\":\"Closeout\"}";
        mvc.perform(post(TEAMS).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isCreated());
        UUID team = fixtures.queryForObject("SELECT team_id FROM public.teams WHERE name='Research Operations'",
                UUID.class);
        UUID first = closeoutMembership(admin, team, "first@example.invalid");
        UUID replacement = closeoutMembership(admin, team, "replacement@example.invalid");
        UUID alternate = closeoutMembership(admin, team, "alternate@example.invalid");
        String path = TEAMS + "/" + team;
        appointForCloseout(admin, path, first);
        transitionForCloseout(admin, path, "ACTIVE");
        UUID firstAssignment = currentAssignment(team, first);
        verifyAlternateCommands(admin, team, path, alternate);
        verifyPlannedReplacement(admin, path, firstAssignment, first, replacement);
        transitionForCloseout(admin, path, "INACTIVE");
        transitionForCloseout(admin, path, "ACTIVE");
        verifyEmergencyRejections(admin, team, path, firstAssignment, replacement);
        verifyDirectEmergency(login("ORG_ADMIN"), team, path, replacement, alternate);
    }

    private UUID closeoutMembership(MockHttpSession admin, UUID team, String address) throws Exception {
        UUID user = createActor("MEMBER", address);
        mvc.perform(post(TEAMS + "/" + team + "/memberships").session(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + user + "\",\"reason\":\"Join\"}"))
                .andExpect(status().isOk());
        return fixtures.queryForObject("""
                SELECT team_membership_id FROM public.team_memberships WHERE team_id=? AND user_id=?
                """, UUID.class, team, user);
    }

    private void appointForCloseout(MockHttpSession admin, String path, UUID membership) throws Exception {
        mvc.perform(post(path + "/leadership").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"membershipId\":\"" + membership + "\",\"reason\":\"Appoint\"}"))
                .andExpect(status().isOk());
    }

    private void transitionForCloseout(MockHttpSession admin, String path, String target) throws Exception {
        mvc.perform(post(path + "/lifecycle").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"" + target + "\",\"reason\":\"Review\"}"))
                .andExpect(status().isOk());
        mvc.perform(get(path).session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleStatus").value(target));
    }

    private UUID currentAssignment(UUID team, UUID membership) {
        return fixtures.queryForObject("""
                SELECT team_leadership_assignment_id FROM public.team_leadership_assignments
                WHERE team_id=? AND team_membership_id=? AND ended_at IS NULL
                """, UUID.class, team, membership);
    }

    private void verifyAlternateCommands(MockHttpSession admin, UUID team, String path, UUID membership)
            throws Exception {
        String body = "{\"membershipId\":\"" + membership + "\",\"priority\":1,\"reason\":\"Ready\"}";
        mvc.perform(post(path + "/alternates").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isOk());
        UUID designation = fixtures.queryForObject("""
                SELECT team_leadership_alternate_id FROM public.team_leadership_alternates
                WHERE team_id=? AND team_membership_id=? AND revoked_at IS NULL
                """, UUID.class, team, membership);
        mvc.perform(post(path + "/alternates/" + designation + "/revoke").session(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(REASON)).andExpect(status().isNoContent());
        assertEquals(Boolean.TRUE, fixtures.queryForObject("""
                SELECT revoked_at IS NOT NULL FROM public.team_leadership_alternates
                WHERE team_leadership_alternate_id=?
                """, Boolean.class, designation));
        mvc.perform(post(path + "/alternates").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isOk());
        assertEquals(0, fixtures.queryForObject("""
                SELECT count(*) FROM public.team_leadership_assignments
                WHERE team_membership_id=? AND ended_at IS NULL
                """, Integer.class, membership));
    }

    private void verifyPlannedReplacement(MockHttpSession admin, String path, UUID assignment,
            UUID membership, UUID replacement) throws Exception {
        String end = path + "/leadership/" + assignment + "/end";
        mvc.perform(post(end).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(REASON)).andExpect(status().isConflict());
        mvc.perform(post(end).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"replacementMembershipId\":\"" + replacement + "\",\"reason\":\"Replace\"}"))
                .andExpect(status().isNoContent());
        assertEquals(Boolean.TRUE, fixtures.queryForObject("""
                SELECT ended_at IS NOT NULL FROM public.team_leadership_assignments
                WHERE team_leadership_assignment_id=?
                """, Boolean.class, assignment));
        mvc.perform(post(path + "/memberships/" + membership + "/end").session(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"endType":"ADMINISTRATIVE","reasonCode":"TEAM_REASSIGNMENT","comment":"Depart"}
                        """)).andExpect(status().isNoContent());
        assertEquals(Boolean.TRUE, fixtures.queryForObject("""
                SELECT left_at IS NOT NULL FROM public.team_memberships WHERE team_membership_id=?
                """, Boolean.class, membership));
    }

    private void verifyEmergencyRejections(MockHttpSession admin, UUID team, String path, UUID ended,
            UUID replacement) throws Exception {
        createActor("ORG_ADMIN");
        MockHttpSession orgAdmin = login("ORG_ADMIN");
        UUID active = currentAssignment(team, replacement);
        String emergency = path + "/leadership/" + active + "/emergency";
        String missing = path + "/leadership/" + UUID.randomUUID() + "/emergency";
        String endedPath = path + "/leadership/" + ended + "/emergency";
        String before = emergencyState(team);
        verifyEmergencyConflict(admin, missing);
        verifyEmergencyConflict(admin, endedPath);
        verifyEmergencyConflict(orgAdmin, missing);
        verifyEmergencyRoleDenials(missing, emergency);
        verifyEmergencyInputDenials(admin, emergency);
        assertEquals(before, emergencyState(team));
    }

    private void verifyEmergencyConflict(MockHttpSession session, String path) throws Exception {
        mvc.perform(post(path).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(EMERGENCY_BODY)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Team operation conflicts with current data."));
    }

    private void verifyEmergencyRoleDenials(String missing, String active) throws Exception {
        for (String address : List.of(email("PROJECT_MANAGER"), email("VIEWER"), email("AUDITOR"),
                "first@example.invalid", "replacement@example.invalid")) {
            MockHttpSession session = loginAddress(address);
            for (String path : List.of(missing, active)) {
                mvc.perform(post(path).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(EMERGENCY_BODY)).andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.detail").value("Access to this resource is denied."));
            }
        }
    }

    private void verifyEmergencyInputDenials(MockHttpSession admin, String path) throws Exception {
        mvc.perform(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(EMERGENCY_BODY))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(path).session(admin).contentType(MediaType.APPLICATION_JSON).content(EMERGENCY_BODY))
                .andExpect(status().isForbidden());
        for (String body : List.of("{}", "{", "{\"type\":\"ACCOUNT_SECURITY\",\"reason\":\"Wrong route\"}",
                "{\"type\":\"EMERGENCY_UNAVAILABILITY\",\"reason\":\" \"}",
                "{\"type\":\"EMERGENCY_UNAVAILABILITY\",\"reason\":\"Review\",\"actorUserId\":null}",
                "{\"type\":\"EMERGENCY_UNAVAILABILITY\",\"reason\":\"" + "x".repeat(501) + "\"}")) {
            mvc.perform(post(path).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(post(path.replaceFirst("/leadership/[^/]+/", "/leadership/not-a-uuid/"))
                .session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(EMERGENCY_BODY))
                .andExpect(status().isBadRequest());
    }

    private String emergencyState(UUID team) {
        return fixtures.queryForObject("""
                SELECT jsonb_build_object(
                 'team',(SELECT to_jsonb(t) FROM public.teams t WHERE team_id=?),
                 'leaders',(SELECT jsonb_agg(to_jsonb(l) ORDER BY team_leadership_assignment_id)
                   FROM public.team_leadership_assignments l WHERE team_id=?),
                 'roles',(SELECT jsonb_agg(to_jsonb(r) ORDER BY assignment_id)
                   FROM public.security_user_roles r WHERE assignment_id IN
                   (SELECT rbac_assignment_id FROM public.team_leadership_assignments WHERE team_id=?)),
                 'alternates',(SELECT jsonb_agg(to_jsonb(a) ORDER BY team_leadership_alternate_id)
                   FROM public.team_leadership_alternates a WHERE team_id=?),
                 'events',(SELECT count(*) FROM public.team_operation_events WHERE team_id=?),
                 'succession',(SELECT count(*) FROM public.team_leadership_succession_events WHERE team_id=?),
                 'alerts',(SELECT count(*) FROM public.team_security_alerts a JOIN public.team_operation_events e
                   USING(event_id) WHERE e.team_id=?),
                 'dispatches',(SELECT count(*) FROM public.team_notification_dispatches d
                   JOIN public.team_operation_events e ON e.event_id=d.operation_event_id WHERE e.team_id=?))::text
                """, String.class, team, team, team, team, team, team, team, team);
    }

    private void verifyDirectEmergency(MockHttpSession admin, UUID team, String path, UUID replacement,
            UUID alternate) throws Exception {
        UUID outgoing = currentAssignment(team, replacement);
        mvc.perform(post(path + "/leadership/" + outgoing + "/emergency").session(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(EMERGENCY_BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resultId").isNotEmpty());
        UUID acting = currentAssignment(team, alternate);
        assertNotNull(acting);
        assertEquals("ACTING", fixtures.queryForObject("""
                SELECT assignment_type FROM public.team_leadership_assignments
                WHERE team_leadership_assignment_id=?
                """, String.class, acting));
        assertEquals("FIRST_ALTERNATE_ACTIVATED", fixtures.queryForObject("""
                SELECT outcome FROM public.team_leadership_succession_events WHERE incoming_assignment_id=?
                """, String.class, acting));
        assertEquals(0, fixtures.queryForObject("""
                SELECT count(*) FROM public.team_leadership_assignments
                WHERE team_leadership_assignment_id=? AND ended_at IS NULL
                """, Integer.class, outgoing));
        mvc.perform(get(path).session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleStatus").value("ACTIVE"));
    }
}
