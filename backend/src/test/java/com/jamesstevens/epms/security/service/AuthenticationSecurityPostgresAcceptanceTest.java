package com.jamesstevens.epms.security.service;

import com.jamesstevens.epms.organization.dto.CreateOrganizationRequest;
import com.jamesstevens.epms.organization.service.OrganizationService;
import com.jamesstevens.epms.user.dto.CreateUserRequest;
import com.jamesstevens.epms.user.entity.UserCredential;
import com.jamesstevens.epms.user.repository.UserCredentialRepository;
import com.jamesstevens.epms.user.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyString;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = "epms.security.audit.hmac-key=acceptance-test-only-key-not-for-production-12345")
class AuthenticationSecurityPostgresAcceptanceTest {
    private static final UUID HUMAN = UUID.fromString("0199a000-0000-7000-8000-000000000001");
    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18")
            .withInitScript("test-db-init.sql");
    @Autowired OrganizationService organizations;
    @Autowired UserService users;
    @Autowired UserCredentialRepository credentials;
    @Autowired AuthenticationSecurityAuditService audit;
    @Autowired AccountUnlockService unlock;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean PasswordScreeningDecisionService screening;
    @MockitoBean ProvisionalCredentialIssuer provisional;
    private UUID organizationId;
    private UUID userId;
    private String email;

    private void fixture() {
        when(screening.decide(anyString())).thenReturn(PasswordScreeningDecisionService.Decision.VERIFIED);
        var tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> {
            var org = organizations.createOrganization(new CreateOrganizationRequest(
                    "EPMS Test Organization", "Authentication security acceptance"));
            organizationId = org.organizationId();
            String employee = "AUTH-" + UUID.randomUUID().toString().substring(0, 12);
            email = employee.toLowerCase() + "@example.com";
            var user = users.createUser(new CreateUserRequest(null, null, HUMAN, employee,
                    "Security", "Tester", email));
            userId = user.userId();
            credentials.saveAndFlush(new UserCredential(userId, "encoded-test-hash", "ARGON2ID", false));
        });
    }

    private HttpServletRequest request(String address) {
        var request = mock(HttpServletRequest.class);
        when(request.getParameter("username")).thenReturn(email);
        when(request.getRemoteAddr()).thenReturn(address);
        return request;
    }

    private int events() {
        return jdbc.queryForObject("SELECT count(*) FROM authentication_security_events WHERE target_user_id = ?",
                Integer.class, userId);
    }

    @AfterEach void cleanup() {
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

    @Test void concurrentFailuresAreSerializedAndEveryAttemptIsAudited() throws Exception {
        fixture();
        var request = request("127.0.0.1");
        try (var executor = Executors.newFixedThreadPool(5)) {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < 5; i++) tasks.add(() -> { audit.recordRejectedLogin(request); return null; });
            for (var future : executor.invokeAll(tasks)) future.get(45, TimeUnit.SECONDS);
        }
        var credential = credentials.findByUserId(userId).orElseThrow();
        assertEquals(1, credential.getLoginLockoutState().stage());
        assertEquals(0, credential.getLoginLockoutState().failuresInStage());
        assertTrue(ProgressiveLoginLockoutPolicy.isBlocked(credential.getLoginLockoutState(), java.time.Instant.now()));
        assertEquals(5, events());
        audit.recordRejectedLogin(request);
        assertEquals(6, events());
        assertEquals(1, credentials.findByUserId(userId).orElseThrow().getLoginLockoutState().stage());
    }

    @Test void invalidAuditIpRollsBackCredentialMutation() {
        fixture();
        var invalid = request("not-an-ip-address");
        assertThrows(RuntimeException.class, () -> audit.recordRejectedLogin(invalid));
        assertEquals(0, events());
        assertEquals(0, credentials.findByUserId(userId).orElseThrow().getFailedAttemptCount());
        assertEquals(0, credentials.findByUserId(userId).orElseThrow().getLoginLockoutState().stage());
    }

    @Test void administratorGrantIsExplicitAndUnlockIsAudited() {
        fixture();
        var request = request("127.0.0.1");
        var actor = new UsernamePasswordAuthenticationToken(email, "", List.of());
        audit.recordRejectedLogin(request);
        assertFalse(unlock.unlock(actor, userId, "Incident reviewed", request));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM authentication_security_events " +
                "WHERE target_user_id = ? AND event_type = 'ACCOUNT_UNLOCK_REJECTED'", Integer.class, userId));
        jdbc.update("INSERT INTO account_unlock_grants(user_id) VALUES (?)", userId);
        assertTrue(unlock.unlock(actor, userId, "Incident reviewed", request));
        assertEquals(0, credentials.findByUserId(userId).orElseThrow().getFailedAttemptCount());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM authentication_security_events " +
                "WHERE target_user_id = ? AND actor_user_id = ? AND event_type = 'ACCOUNT_UNLOCK' " +
                "AND reason = 'Incident reviewed'", Integer.class, userId, userId));
    }

    @Test void successfulLoginResetsCounterAndRetainsAuditHistory() {
        fixture();
        var request = request("127.0.0.1");
        audit.recordRejectedLogin(request);
        audit.recordRejectedLogin(request);
        assertTrue(audit.recordSuccessfulLogin(userId, request));
        assertEquals(0, credentials.findByUserId(userId).orElseThrow().getFailedAttemptCount());
        assertEquals(0, credentials.findByUserId(userId).orElseThrow().getLoginLockoutState().stage());
        assertEquals(3, events());
    }
}
