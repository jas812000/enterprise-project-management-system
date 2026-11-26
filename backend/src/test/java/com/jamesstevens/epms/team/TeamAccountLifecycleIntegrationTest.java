package com.jamesstevens.epms.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** V30 staged account lifecycle operations, tested only in an isolated PostgreSQL 18 container. */
@Testcontainers
class TeamAccountLifecycleIntegrationTest {
    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18")
            .withInitScript("rbac-test-db-init.sql");

    @BeforeAll
    static void migrate() {
        Flyway flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load();
        flyway.migrate();
        flyway.validate();
    }

    @Test
    void migrationPreservesRestrictedBoundary() throws SQLException {
        try (Connection c = connect()) {
            assertTrue(flag(c, "SELECT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version='30' AND success)"));
            for (String role : new String[]{"epms_app", "epms_rbac_runtime"}) {
                assertFalse(flag(c, """
                        SELECT has_function_privilege(?::name,
                                'public.epms_team_disable_account(uuid,uuid,text,text)', 'EXECUTE')
                            """, role));
                assertFalse(flag(c, """
                        SELECT has_table_privilege(?::name, 'public.team_account_lifecycle_events',
                                'INSERT,UPDATE,DELETE,TRUNCATE')
                            """, role));
            }
            execute(c, "SET ROLE epms_app");
            try {
                SQLException denied = assertThrows(SQLException.class, () -> scalar(c,
                        "SELECT public.epms_team_disable_account(?,?,?,?)",
                        UUID.randomUUID(), UUID.randomUUID(), "ACCOUNT_SECURITY", "Denied"));
                assertEquals("42501", denied.getSQLState());
            } finally {
                execute(c, "RESET ROLE");
            }
        }
    }

    @Test
    void accountDisableClosesDependenciesAndNeverPromotesPendingTeam() throws SQLException {
        try (Connection c = connect()) {
            UUID org = id(c, "INSERT INTO public.organizations(name) VALUES ('V30 test') RETURNING organization_id");
            UUID admin = user(c, org, "admin");
            UUID leader = user(c, org, "leader");
            UUID alternate = user(c, org, "alternate");
            UUID outsider = user(c, org, "outsider");
            execute(c, """
                    INSERT INTO public.security_user_roles(user_id,role_id)
                    SELECT ?,role_id FROM public.security_roles WHERE code='SYSTEM_ADMIN'
                    """, admin);
            UUID team = id(c, "INSERT INTO public.teams(organization_id,name) VALUES (?, 'V30 Team') RETURNING team_id", org);
            UUID secondTeam = id(c, "INSERT INTO public.teams(organization_id,name) VALUES (?, 'V30 Second') RETURNING team_id", org);
            String join = "SELECT public.epms_team_join_member(?,?,?,?)";
            UUID member = id(c, join, admin, team, leader, "Join leader");
            UUID secondMember = id(c, join, admin, secondTeam, leader, "Join second team");
            UUID alternateMember = id(c, join, admin, team, alternate, "Join alternate");
            UUID assignment = id(c, "SELECT public.epms_team_assign_permanent(?,?,?,?)",
                    admin, team, member, "Initial leader");
            UUID designation = id(c, "SELECT public.epms_team_designate_alternate(?,?,?,?::smallint,?)",
                    admin, team, alternateMember, 1, "Dormant alternate");
            UUID session = id(c, """
                    INSERT INTO public.user_auth_sessions(user_id,created_at,last_activity_at,
                        idle_expires_at,absolute_expires_at,credential_version)
                    VALUES (?,now(),now(),now()+interval '30 minutes',now()+interval '7 days',now())
                    RETURNING session_id
                    """, leader);
            execute(c, """
                    INSERT INTO public.user_access_tokens(session_id,token_type,token_hash,issued_at,expires_at)
                    VALUES (?,'ACCESS',decode(repeat('ab',32),'hex'),now(),now()+interval '15 minutes'),
                           (?,'RENEWAL',decode(repeat('cd',32),'hex'),now(),now()+interval '7 days')
                    """, session, session);
            String disable = "SELECT public.epms_team_disable_account(?,?,?,?)";
            assertThrows(SQLException.class, () -> scalar(c, disable, outsider, leader, "ACCOUNT_SECURITY", "Denied"));
            assertThrows(SQLException.class, () -> scalar(c, disable, admin, admin, "ACCOUNT_SECURITY", "Self"));
            assertThrows(SQLException.class, () -> scalar(c, disable, admin, leader, "LOGIN_LOCKED", "Not emergency"));
            assertThrows(SQLException.class, () -> scalar(c, disable, admin, leader, "ACCOUNT_SECURITY", " "));
            assertThrows(SQLException.class, () -> execute(c,
                    "UPDATE public.users SET is_active=false WHERE user_id=?", leader));
            try (Connection lock = connect()) {
                lock.setAutoCommit(false);
                try {
                    scalar(lock, "SELECT pg_advisory_xact_lock(1832,12)");
                    execute(c, "SET lock_timeout='300ms'");
                    SQLException blocked = assertThrows(SQLException.class,
                            () -> scalar(c, disable, admin, leader, "ACCOUNT_SECURITY", "Lock test"));
                    assertEquals("55P03", blocked.getSQLState());
                    lock.rollback();
                    scalar(lock, "SELECT pg_advisory_xact_lock(hashtextextended(?::text,82302026))", leader);
                    SQLException credentialBlocked = assertThrows(SQLException.class,
                            () -> scalar(c, disable, admin, leader, "ACCOUNT_SECURITY", "Credential lock test"));
                    assertEquals("55P03", credentialBlocked.getSQLState());
                } finally {
                    lock.rollback();
                    execute(c, "RESET lock_timeout");
                }
            }
            assertTrue(flag(c, "SELECT is_active FROM public.users WHERE user_id=?", leader));
            assertTrue(flag(c, "SELECT revoked_at IS NULL FROM public.user_auth_sessions WHERE session_id=?", session));
            c.setAutoCommit(false);
            try {
                scalar(c, disable, admin, leader, "ACCOUNT_SECURITY", "Rollback test");
            } finally {
                c.rollback();
                c.setAutoCommit(true);
            }
            assertTrue(flag(c, "SELECT is_active FROM public.users WHERE user_id=?", leader));
            assertTrue(flag(c, "SELECT ended_at IS NULL FROM public.team_leadership_assignments WHERE team_leadership_assignment_id=?", assignment));
            assertFalse(flag(c, "SELECT EXISTS (SELECT 1 FROM public.team_account_lifecycle_events WHERE target_user_id=?)", leader));
            UUID event = id(c, disable, admin, leader, "ACCOUNT_SECURITY", "Emergency account disable");
            assertFalse(flag(c, "SELECT is_active FROM public.users WHERE user_id=?", leader));
            assertTrue(flag(c, """
                    SELECT count(*)=2 FROM public.team_memberships
                    WHERE team_membership_id IN (?,?) AND left_at IS NOT NULL
                      AND account_lifecycle_event_id=? AND end_type_code='ACCOUNT_DEACTIVATED'
                    """, member, secondMember, event));
            assertTrue(flag(c, """
                    SELECT l.ended_at IS NOT NULL AND r.revoked_at IS NOT NULL
                        AND r.revoked_by_user_id IS NULL AND r.revoke_team_event_id=l.end_event_id
                    FROM public.team_leadership_assignments l JOIN public.security_user_roles r
                        ON r.assignment_id=l.rbac_assignment_id
                    WHERE l.team_leadership_assignment_id=?
                    """, assignment));
            assertTrue(flag(c, "SELECT revoked_at IS NOT NULL FROM public.user_auth_sessions WHERE session_id=?", session));
            assertTrue(flag(c, "SELECT count(*)=2 FROM public.user_access_tokens WHERE session_id=? AND revoked_at IS NOT NULL", session));
            assertFalse(flag(c, "SELECT EXISTS (SELECT 1 FROM public.security_effective_user_permissions WHERE user_id=?)", leader));
            assertTrue(flag(c, "SELECT revoked_at IS NULL FROM public.team_leadership_alternates WHERE team_leadership_alternate_id=?", designation));
            assertFalse(flag(c, "SELECT EXISTS (SELECT 1 FROM public.team_leadership_succession_events WHERE team_id=?)", team));
            assertTrue(flag(c, "SELECT count(*)=3 FROM public.team_account_operation_links WHERE account_event_id=?", event));
            assertThrows(SQLException.class, () -> execute(c, "UPDATE public.team_account_lifecycle_events SET reason='Tamper' WHERE event_id=?", event));
            assertThrows(SQLException.class, () -> execute(c, "DELETE FROM public.team_account_operation_links WHERE account_event_id=?", event));
            assertThrows(SQLException.class, () -> scalar(c, disable, admin, leader, "ACCOUNT_SECURITY", "Repeat"));
            UUID planned = id(c, disable, admin, alternate, "PLANNED_ARCHIVAL", "Planned departure");
            assertTrue(flag(c, "SELECT is_archived AND NOT is_active FROM public.users WHERE user_id=?", alternate));
            assertTrue(flag(c, "SELECT revoked_at IS NOT NULL FROM public.team_leadership_alternates WHERE team_leadership_alternate_id=?", designation));
            assertTrue(flag(c, "SELECT account_lifecycle_event_id=? AND end_type_code='ACCOUNT_ARCHIVED' FROM public.team_memberships WHERE team_membership_id=?", planned, alternateMember));
            assertFalse(flag(c, "SELECT EXISTS (SELECT 1 FROM public.team_leadership_succession_events WHERE team_id=?)", team));
            assertThrows(SQLException.class, () -> execute(c, "UPDATE public.teams SET lifecycle_status='ACTIVE',is_active=true WHERE team_id=?", team));
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static UUID user(Connection c, UUID org, String name) throws SQLException {
        return id(c, """
                INSERT INTO public.users(organization_id,account_type_id,first_name,last_name,email)
                SELECT ?,account_type_id,?,'Test',? FROM public.account_types WHERE code='HUMAN'
                RETURNING user_id
                """, org, name, name + UUID.randomUUID() + "@example.invalid");
    }

    private static PreparedStatement bind(Connection c, String sql, Object... args) throws SQLException {
        PreparedStatement statement = c.prepareStatement(sql);
        try {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            return statement;
        } catch (SQLException exception) {
            statement.close();
            throw exception;
        }
    }

    private static Object scalar(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement p = bind(c, sql, args); ResultSet rs = p.executeQuery()) {
            assertTrue(rs.next());
            return rs.getObject(1);
        }
    }

    private static UUID id(Connection c, String sql, Object... args) throws SQLException {
        UUID result = (UUID) scalar(c, sql, args);
        assertNotNull(result);
        return result;
    }

    private static boolean flag(Connection c, String sql, Object... args) throws SQLException {
        return Boolean.TRUE.equals(scalar(c, sql, args));
    }

    private static void execute(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement p = bind(c, sql, args)) {
            p.execute();
        }
    }
}
