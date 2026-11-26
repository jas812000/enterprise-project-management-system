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

/** V29 staged operations, tested only in an isolated PostgreSQL 18 container. */
@Testcontainers
class TeamProtectedOperationsIntegrationTest {
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
    void migrationAndRestrictedRuntimeBoundary() throws SQLException {
        try (Connection c = connect()) {
            assertTrue(flag(c, "SELECT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version='29' AND success)"));
            for (String role : new String[]{"epms_app", "epms_rbac_runtime"}) {
                for (String table : new String[]{"team_operation_events", "team_security_alerts",
                        "team_leadership_assignments", "team_leadership_alternates", "team_memberships", "teams"}) {
                    assertFalse(flag(c, "SELECT has_table_privilege(?::name, 'public.' || ?, 'INSERT,UPDATE,DELETE,TRUNCATE')",
                            role, table), role + " can mutate " + table);
                }
                assertFalse(flag(c, """
                        SELECT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
                        WHERE n.nspname='public' AND p.proname IN
                          ('epms_team_require_admin','epms_team_record_event','epms_team_eligible_member',
                           'epms_team_has_eligible_leader','epms_team_open_leadership','epms_team_close_leadership',
                           'epms_team_assign_permanent','epms_team_end_planned','epms_team_designate_alternate',
                           'epms_team_emergency_succession','epms_team_join_member','epms_team_leave_member',
                           'epms_team_revoke_alternate') AND has_function_privilege(?::name,p.oid,'EXECUTE'))
                        """, role), role + " can execute staged functions");
            }
            execute(c, "SET ROLE epms_app");
            try {
                SQLException denied = assertThrows(SQLException.class, () -> scalar(c,
                        "SELECT public.epms_team_join_member(?::uuid,?::uuid,?::uuid,?)",
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Denied"));
                assertEquals("42501", denied.getSQLState());
            } finally {
                execute(c, "RESET ROLE");
            }
            assertTrue(flag(c, "SELECT count(*)=35 FROM public.security_role_permissions"));
        }
    }

    @Test
    void plannedOperationsAndDormantAlternatesPreserveStagedBoundary() throws SQLException {
        try (Connection c = connect()) {
            UUID org = id(c, "INSERT INTO public.organizations(name) VALUES ('V29 test') RETURNING organization_id");
            UUID admin = user(c, org, "admin");
            UUID leader = user(c, org, "leader");
            UUID alternate = user(c, org, "alternate");
            UUID replacement = user(c, org, "replacement");
            UUID outsider = user(c, org, "outsider");
            execute(c, """
                    INSERT INTO public.security_user_roles(user_id,role_id)
                    SELECT ?,role_id FROM public.security_roles WHERE code='SYSTEM_ADMIN'
                    """, admin);
            UUID team = id(c, "INSERT INTO public.teams(organization_id,name) VALUES (?, 'V29 Team') RETURNING team_id", org);
            String join = "SELECT public.epms_team_join_member(?,?,?,?)";
            assertThrows(SQLException.class, () -> scalar(c, join, outsider, team, leader, "Unauthorized"));
            assertThrows(SQLException.class, () -> scalar(c, join, admin, team, leader, " "));
            // An independent transaction holding the shared RBAC lock blocks a Team operation.
            try (Connection lock = connect()) {
                lock.setAutoCommit(false);
                try {
                    scalar(lock, "SELECT pg_advisory_xact_lock(1832,12)");
                    execute(c, "SET lock_timeout='300ms'");
                    SQLException blocked = assertThrows(SQLException.class,
                            () -> scalar(c, join, admin, team, leader, "Lock test"));
                    assertEquals("55P03", blocked.getSQLState());
                } finally {
                    lock.rollback();
                    execute(c, "RESET lock_timeout");
                }
            }
            UUID member = id(c, join, admin, team, leader, "Join leader");
            UUID alternateMember = id(c, join, admin, team, alternate, "Join alternate");
            UUID replacementMember = id(c, join, admin, team, replacement, "Join replacement");
            String assign = "SELECT public.epms_team_assign_permanent(?,?,?,?)";
            assertThrows(SQLException.class, () -> scalar(c, assign, outsider, team, member, "Denied"));
            UUID assignment = id(c, assign, admin, team, member, "Initial leader");
            assertTrue(flag(c, "SELECT public.epms_team_has_eligible_leader(?)", team));
            assertTrue(flag(c, """
                    SELECT EXISTS (SELECT 1 FROM public.team_leadership_assignments l
                    JOIN public.security_user_roles r ON r.assignment_id=l.rbac_assignment_id
                    WHERE l.team_leadership_assignment_id=? AND l.user_id=? AND r.revoked_at IS NULL)
                    """, assignment, leader));
            String designate = "SELECT public.epms_team_designate_alternate(?,?,?,?::smallint,?)";
            UUID designation = id(c, designate, admin, team, alternateMember, 1, "Dormant alternate");
            assertFalse(flag(c, "SELECT EXISTS (SELECT 1 FROM public.security_user_roles WHERE user_id=? AND revoked_at IS NULL)", alternate));
            assertThrows(SQLException.class, () -> scalar(c, designate, admin, team, replacementMember, 1, "Duplicate priority"));
            assertThrows(SQLException.class, () -> scalar(c, designate, outsider, team, replacementMember, 2, "Denied"));
            assertThrows(SQLException.class, () -> execute(c, "UPDATE public.teams SET lifecycle_status='ACTIVE',is_active=true WHERE team_id=?", team));
            String emergency = "SELECT public.epms_team_emergency_succession(?,?,?,?,?)";
            assertThrows(SQLException.class, () -> scalar(c, emergency, admin, team, assignment, "PLANNED_REMOVAL", "Not an emergency"));
            assertThrows(SQLException.class, () -> scalar(c, emergency, admin, team, assignment, "ACCOUNT_SECURITY", "Activation remains closed"));
            assertThrows(SQLException.class, () -> scalar(c,
                    "SELECT public.epms_team_leave_member(?,?,?,?,?,?)", admin, team, member,
                    "ADMINISTRATIVE", "TEAM_REASSIGNMENT", "End leadership first"));
            assertThrows(SQLException.class, () -> execute(c, """
                    UPDATE public.security_user_roles SET revoked_at=clock_timestamp(),revoked_by_user_id=?
                    WHERE assignment_id=(SELECT rbac_assignment_id FROM public.team_leadership_assignments
                    WHERE team_leadership_assignment_id=?)
                    """, admin, assignment));
            scalar(c, "SELECT public.epms_team_end_planned(?,?,?,?,?)", admin, team, assignment, replacementMember, "Planned replacement");
            assertTrue(flag(c, "SELECT ended_at IS NOT NULL FROM public.team_leadership_assignments WHERE team_leadership_assignment_id=?", assignment));
            assertTrue(flag(c, """
                    SELECT r.revoked_at IS NOT NULL FROM public.team_leadership_assignments l
                    JOIN public.security_user_roles r ON r.assignment_id=l.rbac_assignment_id
                    WHERE l.team_leadership_assignment_id=?
                    """, assignment));
            assertTrue(flag(c, "SELECT public.epms_team_has_eligible_leader(?)", team));
            assertTrue(flag(c, "SELECT revoked_at IS NULL FROM public.team_leadership_alternates WHERE team_leadership_alternate_id=?", designation));
            assertFalse(flag(c, "SELECT EXISTS (SELECT 1 FROM public.team_leadership_succession_events WHERE team_id=?)", team));
            scalar(c, "SELECT public.epms_team_leave_member(?,?,?,?,?,?)", admin, team, member,
                    "ADMINISTRATIVE", "TEAM_REASSIGNMENT", "Planned departure");
            UUID rejoined = id(c, join, admin, team, leader, "Rejoin");
            assertTrue(flag(c, "SELECT membership_period_number=2 FROM public.team_memberships WHERE team_membership_id=?", rejoined));
            scalar(c, "SELECT public.epms_team_revoke_alternate(?,?,?,?)", admin, team, designation, "No longer available");
            assertTrue(flag(c, "SELECT revoked_at IS NOT NULL FROM public.team_leadership_alternates WHERE team_leadership_alternate_id=?", designation));
            assertTrue(flag(c, "SELECT EXISTS (SELECT 1 FROM public.team_operation_events WHERE team_id=?)", team));
            assertThrows(SQLException.class, () -> execute(c, "UPDATE public.team_operation_events SET reason='Tamper' WHERE team_id=?", team));
            assertThrows(SQLException.class, () -> execute(c, "DELETE FROM public.team_operation_events WHERE team_id=?", team));
            assertThrows(SQLException.class, () -> execute(c, "DELETE FROM public.team_leadership_assignments WHERE team_leadership_assignment_id=?", assignment));
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
