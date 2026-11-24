package com.jamesstevens.epms.security.rbac;

import com.jamesstevens.epms.security.service.PasswordScreeningDecisionService;
import com.jamesstevens.epms.security.service.ProvisionalCredentialIssuer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.util.UUID;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real Spring Security filter-chain checks against disposable PostgreSQL. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = "epms.security.audit.hmac-key=acceptance-test-only-key-not-for-production-12345")
class RbacAdministrationSecurityFilterAcceptanceTest {
    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18")
            .withInitScript("test-db-init.sql");
    @Autowired MockMvc mvc;
    @MockitoBean PasswordScreeningDecisionService screening;
    @MockitoBean ProvisionalCredentialIssuer provisional;

    @Test void anonymousAssignmentWithCsrfIsUnauthorized() throws Exception {
        mvc.perform(post("/api/security/role-assignments").with(csrf())
                .contentType("application/json")
                .content("{\"targetUserId\":\"" + UUID.randomUUID()
                        + "\",\"roleCode\":\"PROJECT_MANAGER\",\"reason\":\"test\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test void anonymousRevocationWithCsrfIsUnauthorized() throws Exception {
        mvc.perform(post("/api/security/role-assignments/{id}/revoke", UUID.randomUUID())
                .with(csrf()).contentType("application/json")
                .content("{\"reason\":\"test\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test void assignmentWithoutCsrfIsForbidden() throws Exception {
        mvc.perform(post("/api/security/role-assignments")
                .contentType("application/json")
                .content("{\"targetUserId\":\"" + UUID.randomUUID()
                        + "\",\"roleCode\":\"PROJECT_MANAGER\",\"reason\":\"test\"}"))
                .andExpect(status().isForbidden());
    }

    @Test void revocationWithoutCsrfIsForbidden() throws Exception {
        mvc.perform(post("/api/security/role-assignments/{id}/revoke", UUID.randomUUID())
                .contentType("application/json")
                .content("{\"reason\":\"test\"}"))
                .andExpect(status().isForbidden());
    }
}
