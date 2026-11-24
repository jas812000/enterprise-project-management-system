package com.jamesstevens.epms.security.web;

import com.jamesstevens.epms.security.service.PasswordScreeningDecisionService;
import com.jamesstevens.epms.security.service.ProvisionalCredentialIssuer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP filter-chain tests using real Spring Security, Flyway and isolated PostgreSQL. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = "epms.security.audit.hmac-key=acceptance-test-only-key-not-for-production-12345")
class AuthenticationSecurityHttpAcceptanceTest {
    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18")
            .withInitScript("test-db-init.sql");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PasswordScreeningDecisionService screening;
    @MockitoBean ProvisionalCredentialIssuer provisional;

    @Test void loginWithoutCsrfIsForbiddenBeforeCredentialProcessing() throws Exception {
        mvc.perform(post("/api/auth/login")
                .param("username", "unknown-" + UUID.randomUUID() + "@example.com")
                .param("password", "wrong"))
                .andExpect(status().isForbidden());
    }

    @Test void unknownAccountLoginWithCsrfReturnsGeneric401AndPersistsEvent() throws Exception {
        String unknown = "unknown-" + UUID.randomUUID() + "@example.com";
        mvc.perform(post("/api/auth/login").with(csrf())
                .param("username", unknown).param("password", "wrong"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value("Invalid username or password."));
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM authentication_security_events " +
                "WHERE event_type = 'LOGIN_FAILED' AND target_user_id IS NULL", Integer.class);
        assertNotNull(count);
        assertTrue(count > 0, "Unknown account rejection must be audited");
    }

    @Test void anonymousAdministratorUnlockWithCsrfCannotSucceed() throws Exception {
        mvc.perform(post("/api/auth/admin/users/" + UUID.randomUUID() + "/unlock")
                .with(csrf()).contentType("application/json")
                .content("{\"reason\":\"incident reviewed\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test void administratorUnlockWithoutCsrfIsForbidden() throws Exception {
        mvc.perform(post("/api/auth/admin/users/" + UUID.randomUUID() + "/unlock")
                .contentType("application/json")
                .content("{\"reason\":\"incident reviewed\"}"))
                .andExpect(status().isForbidden());
    }

    @Test void csrfEndpointProvidesTokenWithoutAuthentication() throws Exception {
        mvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.headerName").isNotEmpty());
    }
}
