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
class AuthenticationTokenAuthorityIntegrationTest {
    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18")
            .withInitScript("test-db-init.sql");
    @Autowired JdbcTemplate jdbc;

    @Test void restrictedOwnershipAndRuntimeDml() {
        assertEquals(Boolean.FALSE, jdbc.queryForObject(
                "SELECT has_table_privilege('epms_app','public.user_access_tokens','INSERT')", Boolean.class));
        assertEquals(Boolean.FALSE, jdbc.queryForObject(
                "SELECT has_table_privilege('epms_app','public.user_auth_sessions','UPDATE')", Boolean.class));
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                "SELECT has_function_privilege('epms_app','public.epms_auth_rotate(bytea,bytea,bytea)','EXECUTE')", Boolean.class));
        assertEquals(Boolean.FALSE, jdbc.queryForObject(
                "SELECT has_function_privilege('epms_rbac_runtime','public.epms_auth_rotate(bytea,bytea,bytea)','EXECUTE')", Boolean.class));
        assertEquals(Boolean.FALSE, jdbc.queryForObject(
                "SELECT has_function_privilege('epms_bootstrap_runtime','public.epms_auth_issue(uuid,bytea,bytea)','EXECUTE')", Boolean.class));
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                "SELECT NOT rolcanlogin AND NOT rolinherit FROM pg_roles WHERE rolname='epms_auth_function_owner'", Boolean.class));
        assertEquals("epms_auth_function_owner", jdbc.queryForObject("""
                SELECT pg_get_userbyid(p.proowner) FROM pg_proc p
                WHERE p.oid='public.epms_auth_rotate(bytea,bytea,bytea)'::regprocedure
                """, String.class));
    }

    @Test void allProtectedFunctionsHaveRestrictedExecutionAndOwnership() {
        for (String signature : new String[] {
                "public.epms_auth_issue(uuid,bytea,bytea)",
                "public.epms_auth_rotate(bytea,bytea,bytea)",
                "public.epms_auth_revoke(uuid,text)"}) {
            assertEquals(Boolean.TRUE, jdbc.queryForObject(
                    "SELECT has_function_privilege('epms_app', CAST(? AS regprocedure), 'EXECUTE')",
                    Boolean.class, signature));
            for (String restricted : new String[] {"epms_rbac_runtime", "epms_bootstrap_runtime"}) {
                assertEquals(Boolean.FALSE, jdbc.queryForObject(
                        "SELECT has_function_privilege(CAST(? AS name), CAST(? AS regprocedure), 'EXECUTE')",
                        Boolean.class, restricted, signature), restricted + " must not execute " + signature);
            }
            assertEquals(Boolean.TRUE, jdbc.queryForObject(
                    """
                    SELECT NOT EXISTS (SELECT 1 FROM pg_proc p,
                      LATERAL aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) a
                      WHERE p.oid=CAST(? AS regprocedure)
                        AND a.grantee=0 AND a.privilege_type='EXECUTE')
                    """,
                    Boolean.class, signature), "PUBLIC must not execute " + signature);
            assertEquals("epms_auth_function_owner", jdbc.queryForObject("""
                    SELECT pg_get_userbyid(proowner) FROM pg_proc
                    WHERE oid=CAST(? AS regprocedure)
                    """, String.class, signature));
            assertEquals(Boolean.TRUE, jdbc.queryForObject("""
                    SELECT prosecdef AND proconfig @> ARRAY['search_path=pg_catalog']::text[]
                    FROM pg_proc WHERE oid=CAST(? AS regprocedure)
                    """, Boolean.class, signature));
        }
    }

    @Test void runtimeRolesCannotMutateAuditHistoryOrProtectedTables() {
        for (String role : new String[] {"epms_app", "epms_rbac_runtime", "epms_bootstrap_runtime"}) {
            for (String privilege : new String[] {"UPDATE", "DELETE", "TRUNCATE"}) {
                assertEquals(Boolean.FALSE, jdbc.queryForObject(
                        "SELECT has_table_privilege(CAST(? AS name), 'public.authentication_security_events', ?)",
                        Boolean.class, role, privilege), role + " must not " + privilege + " audit history");
            }
            for (String table : new String[] {"public.user_auth_sessions", "public.user_access_tokens"}) {
                for (String privilege : new String[] {"INSERT", "UPDATE", "DELETE", "TRUNCATE"}) {
                    assertEquals(Boolean.FALSE, jdbc.queryForObject(
                            "SELECT has_table_privilege(CAST(? AS name), CAST(? AS text), ?)",
                            Boolean.class, role, table, privilege), role + " must not " + privilege + " " + table);
                }
            }
        }
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                "SELECT has_table_privilege('epms_app', 'public.authentication_security_events', 'INSERT')",
                Boolean.class));
    }

    @Test void invalidOperationsDoNotCreateCredentials() {
        Integer before = jdbc.queryForObject("SELECT count(*) FROM user_access_tokens", Integer.class);
        assertEquals("REJECTED", jdbc.queryForObject("SELECT public.epms_auth_rotate(?,?,?)",
                String.class, new byte[32], new byte[32], new byte[32]));
        assertEquals(before, jdbc.queryForObject("SELECT count(*) FROM user_access_tokens", Integer.class));
    }
}
