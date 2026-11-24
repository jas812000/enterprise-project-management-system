package com.jamesstevens.epms.security.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = "epms.security.audit.hmac-key=acceptance-test-only-key-not-for-production-12345")
class AuthenticationAccessValidationPrivilegeIntegrationTest {
    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18")
            .withInitScript("test-db-init.sql");
    @Autowired JdbcTemplate jdbc;

    @Test
    void validationFunctionHasRestrictedPrivilegesAndRejectsInvalidDigests() {
        String signature = "public.epms_auth_validate_access(bytea)";
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                "SELECT has_function_privilege('epms_app', CAST(? AS regprocedure), 'EXECUTE')",
                Boolean.class, signature));
        for (String role : new String[] {"epms_rbac_runtime", "epms_bootstrap_runtime"}) {
            assertEquals(Boolean.FALSE, jdbc.queryForObject(
                    "SELECT has_function_privilege(CAST(? AS name), CAST(? AS regprocedure), 'EXECUTE')",
                    Boolean.class, role, signature));
        }
        assertEquals(Boolean.FALSE, jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_proc p, LATERAL aclexplode(p.proacl) a "
                + "WHERE p.oid=CAST(? AS regprocedure) AND a.grantee=0 AND a.privilege_type='EXECUTE')",
                Boolean.class, signature));
        assertEquals("epms_auth_function_owner", jdbc.queryForObject(
                "SELECT pg_get_userbyid(proowner) FROM pg_proc WHERE oid=CAST(? AS regprocedure)",
                String.class, signature));
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                "SELECT prosecdef AND proconfig @> ARRAY['search_path=pg_catalog']::text[] "
                + "FROM pg_proc WHERE oid=CAST(? AS regprocedure)",
                Boolean.class, signature));
        assertNull(jdbc.queryForObject("SELECT public.epms_auth_validate_access(?)",
                java.util.UUID.class, new byte[32]));
        assertNull(jdbc.queryForObject("SELECT public.epms_auth_validate_access(?)",
                java.util.UUID.class, new byte[31]));
    }
}
