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
class AuthenticationTokenSchemaIntegrationTest {
    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18")
            .withInitScript("test-db-init.sql");
    @Autowired JdbcTemplate jdbc;

    @Test void schemaExistsWithExpectedTokenConstraintsAndIndexes() {
        assertEquals(2, jdbc.queryForObject("""
            SELECT count(*) FROM information_schema.tables
            WHERE table_schema = 'public' AND table_name IN
            ('user_auth_sessions', 'user_access_tokens')
            """, Integer.class));
        assertEquals(1, jdbc.queryForObject("""
            SELECT count(*) FROM pg_constraint
            WHERE conname = 'uq_auth_token_hash'
            """, Integer.class));
        assertEquals(1, jdbc.queryForObject("""
            SELECT count(*) FROM pg_indexes
            WHERE schemaname = 'public' AND indexname = 'uq_auth_active_renewal_per_session'
            """, Integer.class));
    }

    @Test void runtimePrivilegesAreSeparated() {
        assertEquals(Boolean.FALSE, jdbc.queryForObject(
            "SELECT has_table_privilege('epms_app', 'public.user_access_tokens', 'SELECT')", Boolean.class));
        assertEquals(Boolean.FALSE, jdbc.queryForObject(
            "SELECT has_table_privilege('epms_app', 'public.user_auth_sessions', 'INSERT')", Boolean.class));
        assertEquals(Boolean.FALSE, jdbc.queryForObject(
            "SELECT has_table_privilege('epms_rbac_runtime', 'public.user_access_tokens', 'SELECT')", Boolean.class));
        assertEquals(Boolean.FALSE, jdbc.queryForObject(
            "SELECT has_table_privilege('epms_bootstrap_runtime', 'public.user_auth_sessions', 'INSERT')", Boolean.class));
    }

    @Test void databaseDeadlineEqualityIsExpiredAtMicrosecondPrecision() {
        assertEquals(Boolean.TRUE, jdbc.queryForObject("""
            SELECT (TIMESTAMPTZ '2026-01-01 00:00:00.000001+00'
                    <= TIMESTAMPTZ '2026-01-01 00:00:00.000001+00')
            """, Boolean.class));
        assertEquals(Boolean.FALSE, jdbc.queryForObject("""
            SELECT (TIMESTAMPTZ '2026-01-01 00:00:00.000002+00'
                    <= TIMESTAMPTZ '2026-01-01 00:00:00.000001+00')
            """, Boolean.class));
        assertEquals(Boolean.TRUE, jdbc.queryForObject("""
            SELECT (TIMESTAMPTZ '2026-01-01 00:00:00.000001+00'
                    <= TIMESTAMPTZ '2026-01-01 00:00:00.000002+00')
            """, Boolean.class));
    }

    @Test void auditAcceptsTokenEventTypes() {
        assertEquals(1, jdbc.queryForObject("""
            SELECT count(*) FROM pg_constraint
            WHERE conname = 'ck_auth_event_type'
              AND pg_get_constraintdef(oid) LIKE '%TOKEN_REPLAY_DETECTED%'
            """, Integer.class));
    }
}
