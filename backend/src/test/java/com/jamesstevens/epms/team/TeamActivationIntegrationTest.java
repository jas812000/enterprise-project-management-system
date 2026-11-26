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

/** V31 protected lifecycle and active-Team succession, tested only in an isolated PostgreSQL 18 container. */
@Testcontainers
class TeamActivationIntegrationTest {
    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18")
            .withInitScript("rbac-test-db-init.sql");

    @BeforeAll
    static void migrate() throws SQLException {
        Flyway flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load();
        flyway.migrate();
        flyway.validate();
        if (Boolean.getBoolean("epms.team.fixture.diagnostics")) {
            installDiagnosticValidator();
        }
    }

    private static void installDiagnosticValidator() throws SQLException {
        try (Connection c = connect()) {
            execute(c, """
                    CREATE OR REPLACE FUNCTION public.epms_team_validate_transaction() RETURNS trigger
                    LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
                    DECLARE v_diagnostic jsonb;
                    BEGIN
                     PERFORM pg_advisory_xact_lock(1832,12);
                     IF EXISTS (SELECT 1 FROM public.teams t WHERE t.is_active
                      AND NOT public.epms_team_has_eligible_leader(t.team_id)) THEN
                      RAISE EXCEPTION 'Active Team must retain eligible membership and leadership';
                     END IF;
                     IF EXISTS (SELECT 1 FROM public.security_user_roles x JOIN public.security_roles r USING(role_id)
                      WHERE r.code='TEAM_LEAD' AND x.revoked_at IS NULL AND NOT EXISTS (
                       SELECT 1 FROM public.team_leadership_assignments l WHERE l.rbac_assignment_id=x.assignment_id
                       AND l.user_id=x.user_id AND l.ended_at IS NULL AND public.epms_team_eligible_member(l.team_membership_id,l.team_id))) THEN
                      RAISE EXCEPTION 'TEAM_LEAD requires an open eligible Team relationship';
                     END IF;
                     WITH examined AS MATERIALIZED (
                      SELECT public.epms_team_eligible_member(l.team_membership_id,l.team_id) AS eligible,
                       x.assignment_id IS NOT NULL AS role_found,
                       x.user_id IS NOT DISTINCT FROM l.user_id AS same_user,
                       r.code IS NOT DISTINCT FROM 'TEAM_LEAD' AS correct_role,
                       r.is_active IS TRUE AS role_active,
                       x.revoked_at IS NULL AS not_revoked,
                       x.valid_from, x.valid_until, clock_timestamp() AS checked_at
                      FROM public.team_leadership_assignments l JOIN public.teams t USING(team_id)
                      LEFT JOIN public.security_user_roles x ON x.assignment_id=l.rbac_assignment_id
                      LEFT JOIN public.security_roles r ON r.role_id=x.role_id
                      WHERE l.ended_at IS NULL
                     ) SELECT jsonb_agg(jsonb_build_object('eligible',eligible,'role_found',role_found,
                      'same_user',same_user,'correct_role',correct_role,'role_active',role_active,
                      'not_revoked',not_revoked,'valid_from',valid_from,'valid_until',valid_until,
                      'checked_at',checked_at,'clock_delta_us',extract(epoch FROM (checked_at-valid_from))*1000000))
                     INTO v_diagnostic FROM examined WHERE NOT (eligible AND role_found AND same_user
                      AND correct_role AND role_active AND not_revoked AND valid_from<=checked_at AND valid_until IS NULL);
                     IF v_diagnostic IS NOT NULL THEN
                      RAISE EXCEPTION 'Active Team leadership requires valid scoped RBAC' USING DETAIL=v_diagnostic::text;
                     END IF;
                     RETURN NULL;
                    END $$;
                    """);
        }
    }

    private record Fixture(UUID admin, UUID team, UUID leader, UUID assignment) { }

    private static Fixture fixture(Connection c) throws SQLException {
        UUID org = id(c, """
                INSERT INTO public.organizations(name) VALUES ('V31 test') RETURNING organization_id
                """);
        return fixture(c, org);
    }

    private static Fixture fixture(Connection c, UUID org) throws SQLException {
        UUID admin = user(c, org, "admin");
        UUID leader = user(c, org, "leader");
        execute(c, "INSERT INTO public.security_user_roles(user_id,role_id) SELECT ?,role_id FROM public.security_roles WHERE code='SYSTEM_ADMIN'", admin);
        UUID team = id(c, "INSERT INTO public.teams(organization_id,name) VALUES (?,?) RETURNING team_id", org, "V31 " + UUID.randomUUID());
        UUID member = join(c, admin, team, leader);
        UUID assignment = id(c, "SELECT public.epms_team_assign_permanent(?,?,?,?)", admin, team, member, "Assign leader");
        return new Fixture(admin, team, leader, assignment);
    }

    private static Fixture fixtureForScenario(Connection c) throws SQLException {
        if (!flag(c, "SELECT EXISTS (SELECT 1 FROM public.organizations)")) {
            return fixture(c);
        }
        return fixture(c, id(c, "SELECT organization_id FROM public.organizations LIMIT 1"));
    }

    private static UUID org(Connection c, Fixture f) throws SQLException {
        return id(c, "SELECT organization_id FROM public.teams WHERE team_id=?", f.team());
    }

    private static UUID join(Connection c, UUID admin, UUID team, UUID user) throws SQLException {
        return id(c, "SELECT public.epms_team_join_member(?,?,?,?)", admin, team, user, "Join");
    }

    private static UUID alternate(Connection c, Fixture f, UUID user, int priority) throws SQLException {
        return id(c, "SELECT public.epms_team_designate_alternate(?,?,?,?::smallint,?)", f.admin(), f.team(),
                join(c, f.admin(), f.team(), user), priority, "Designate alternate");
    }

    private static UUID transition(Connection c, Fixture f, String state) throws SQLException {
        return id(c, "SELECT public.epms_team_transition(?,?,?,?)", f.admin(), f.team(), state, "Lifecycle test");
    }

    private static UUID disable(Connection c, Fixture f, String kind) throws SQLException {
        return id(c, "SELECT public.epms_team_disable_account(?,?,?,?)", f.admin(), f.leader(), kind, "Account lifecycle test");
    }

    @Test
    void activationRequiresAuthorizedOperationAndScope() throws SQLException {
        try (Connection c = connect()) {
            Fixture f = fixtureForScenario(c);
            UUID outsider = user(c, org(c, f), "outsider");
            UUID otherTeam = id(c, "INSERT INTO public.teams(organization_id,name) VALUES (?,?) RETURNING team_id", org(c, f), "Other " + UUID.randomUUID());
            assertTrue(flag(c, "SELECT EXISTS(SELECT 1 FROM flyway_schema_history WHERE version='31' AND success)"));
            assertThrows(SQLException.class, () -> scalar(c, "SELECT public.epms_team_transition(?,?,?,?)", outsider, f.team(), "ACTIVE", "Denied"));
            assertThrows(SQLException.class, () -> scalar(c, "SELECT public.epms_team_transition(?,?,?,?)", f.admin(), otherTeam, "ACTIVE", "No leader"));
            assertThrows(SQLException.class, () -> execute(c, "UPDATE public.teams SET lifecycle_status='ACTIVE',is_active=true WHERE team_id=?", f.team()));
            assertFalse(flag(c, "SELECT public.epms_team_has_permission(?,?,'TASK_ASSIGN')", f.leader(), f.team()));
            UUID event = transition(c, f, "ACTIVE");
            assertTrue(flag(c, "SELECT public.epms_team_has_permission(?,?,'TASK_ASSIGN')", f.leader(), f.team()));
            assertFalse(flag(c, "SELECT public.epms_team_has_permission(?,?,'TASK_ASSIGN')", f.leader(), otherTeam));
            assertFalse(flag(c, "SELECT public.epms_team_has_permission(?,?,'TASK_ASSIGN')", outsider, f.team()));
            assertFalse(flag(c, "SELECT EXISTS (SELECT 1 FROM public.security_effective_user_permissions WHERE user_id=? AND permission_code='TASK_ASSIGN')", f.leader()));
            assertThrows(SQLException.class, () -> execute(c, "UPDATE public.team_lifecycle_events SET reason='Tamper' WHERE event_id=?", event));
            assertThrows(SQLException.class, () -> execute(c, "DELETE FROM public.team_lifecycle_events WHERE event_id=?", event));
            assertThrows(SQLException.class, () -> execute(c, "UPDATE public.teams SET lifecycle_status='INACTIVE',is_active=false WHERE team_id=?", f.team()));
            assertThrows(SQLException.class, () -> transition(c, f, "ARCHIVED"));
            transition(c, f, "INACTIVE");
            assertFalse(flag(c, "SELECT public.epms_team_has_permission(?,?,'TASK_ASSIGN')", f.leader(), f.team()));
            transition(c, f, "ARCHIVED");
            assertTrue(flag(c, "SELECT is_archived AND NOT is_active FROM public.teams WHERE team_id=?", f.team()));
            assertFalse(flag(c, "SELECT EXISTS (SELECT 1 FROM public.team_memberships WHERE team_id=? AND left_at IS NULL)", f.team()));
            assertFalse(flag(c, "SELECT EXISTS (SELECT 1 FROM public.security_user_roles WHERE user_id=? AND revoked_at IS NULL)", f.leader()));
            assertThrows(SQLException.class, () -> transition(c, f, "ACTIVE"));
        }
    }

    @Test
    void firstAlternateBecomesActingAfterEmergencyAccountDisable() throws SQLException {
        try (Connection c = connect()) {
            Fixture f = fixtureForScenario(c);
            UUID first = user(c, org(c, f), "first");
            UUID second = user(c, org(c, f), "second");
            UUID designation = alternate(c, f, first, 1);
            UUID backup = alternate(c, f, second, 2);
            transition(c, f, "ACTIVE");
            assertFalse(flag(c, "SELECT public.epms_team_has_permission(?,?,'TASK_ASSIGN')", first, f.team()));
            assertTrue(flag(c, "SELECT count(*)=2 FROM public.epms_team_alternate_readiness(?) WHERE readiness='ELIGIBLE'", f.team()));
            assertThrows(SQLException.class, () -> disable(c, f, "PLANNED_DEACTIVATION"));
            assertTrue(flag(c, "SELECT is_active FROM public.users WHERE user_id=?", f.leader()));
            assertFalse(flag(c, "SELECT EXISTS(SELECT 1 FROM public.team_leadership_succession_events WHERE team_id=?)", f.team()));
            disable(c, f, "ACCOUNT_SECURITY");
            assertTrue(flag(c, "SELECT is_active FROM public.teams WHERE team_id=?", f.team()));
            assertTrue(flag(c, "SELECT public.epms_team_has_permission(?,?,'TASK_ASSIGN')", first, f.team()));
            assertFalse(flag(c, "SELECT public.epms_team_has_permission(?,?,'TASK_ASSIGN')", f.leader(), f.team()));
            assertFalse(flag(c, "SELECT is_active FROM public.users WHERE user_id=?", f.leader()));
            assertTrue(flag(c, "SELECT revoked_at IS NOT NULL AND revocation_event_id IS NOT NULL FROM public.team_leadership_alternates WHERE team_leadership_alternate_id=?", designation));
            assertTrue(flag(c, "SELECT revoked_at IS NULL FROM public.team_leadership_alternates WHERE team_leadership_alternate_id=?", backup));
            assertTrue(flag(c, """
                    SELECT EXISTS(SELECT 1 FROM public.team_leadership_succession_events s
                    JOIN public.team_leadership_assignments l ON l.team_leadership_assignment_id=s.incoming_assignment_id
                    WHERE s.team_id=? AND s.outcome='FIRST_ALTERNATE_ACTIVATED' AND l.user_id=?
                      AND l.assignment_type='ACTING' AND l.assigned_by_user_id IS NULL)
                    """, f.team(), first));
        }
    }

    @Test
    void ineligibleFirstAlternateFallsBackToSecond() throws SQLException {
        try (Connection c = connect()) {
            Fixture f = fixtureForScenario(c);
            UUID first = user(c, org(c, f), "first");
            UUID second = user(c, org(c, f), "second");
            alternate(c, f, first, 1);
            alternate(c, f, second, 2);
            scalar(c, "SELECT public.epms_rbac_assign_role(?,?,?,?)", f.admin(), first, "AUDITOR", "Independent auditor role");
            transition(c, f, "ACTIVE");
            assertTrue(flag(c, "SELECT readiness='INELIGIBLE' FROM public.epms_team_alternate_readiness(?) WHERE priority=1", f.team()));
            disable(c, f, "EMERGENCY_TERMINATION");
            assertTrue(flag(c, "SELECT public.epms_team_has_permission(?,?,'TASK_ASSIGN')", second, f.team()));
            assertFalse(flag(c, "SELECT public.epms_team_has_permission(?,?,'TASK_ASSIGN')", first, f.team()));
            assertTrue(flag(c, "SELECT EXISTS(SELECT 1 FROM public.team_leadership_succession_events WHERE team_id=? AND outcome='SECOND_ALTERNATE_ACTIVATED')", f.team()));
        }
    }

    @Test
    void eligibleColeaderPreventsAlternateActivation() throws SQLException {
        try (Connection c = connect()) {
            Fixture f = fixtureForScenario(c);
            UUID coleader = user(c, org(c, f), "coleader");
            UUID candidate = user(c, org(c, f), "candidate");
            id(c, "SELECT public.epms_team_assign_permanent(?,?,?,?)", f.admin(), f.team(), join(c, f.admin(), f.team(), coleader), "Co-leader");
            UUID designation = alternate(c, f, candidate, 1);
            transition(c, f, "ACTIVE");
            disable(c, f, "EMERGENCY_DEACTIVATION");
            assertTrue(flag(c, "SELECT public.epms_team_has_permission(?,?,'TASK_ASSIGN')", coleader, f.team()));
            assertFalse(flag(c, "SELECT public.epms_team_has_permission(?,?,'TASK_ASSIGN')", candidate, f.team()));
            assertTrue(flag(c, "SELECT revoked_at IS NULL FROM public.team_leadership_alternates WHERE team_leadership_alternate_id=?", designation));
            assertTrue(flag(c, "SELECT EXISTS(SELECT 1 FROM public.team_leadership_succession_events WHERE team_id=? AND outcome='OTHER_LEAD_AVAILABLE')", f.team()));
        }
    }

    @Test
    void noSuccessorPersistsAlertBeforeDeactivationAndRollsBackAtomically() throws SQLException {
        try (Connection c = connect()) {
            Fixture f = fixtureForScenario(c);
            transition(c, f, "ACTIVE");
            c.setAutoCommit(false);
            try {
                disable(c, f, "ACCOUNT_SECURITY");
                assertFalse(flag(c, "SELECT is_active FROM public.teams WHERE team_id=?", f.team()));
            } finally {
                c.rollback();
                c.setAutoCommit(true);
            }
            assertTrue(flag(c, "SELECT is_active FROM public.teams WHERE team_id=?", f.team()));
            assertTrue(flag(c, "SELECT is_active FROM public.users WHERE user_id=?", f.leader()));
            assertFalse(flag(c, "SELECT EXISTS(SELECT 1 FROM public.team_leadership_succession_events WHERE team_id=?)", f.team()));
            disable(c, f, "ACCOUNT_SECURITY");
            assertTrue(flag(c, "SELECT lifecycle_status='INACTIVE' AND NOT is_active FROM public.teams WHERE team_id=?", f.team()));
            assertTrue(flag(c, """
                    SELECT EXISTS(SELECT 1 FROM public.team_security_alerts a
                    JOIN public.team_operation_events e ON e.event_id=a.event_id
                    JOIN public.team_lifecycle_events t ON t.team_id=e.team_id AND t.to_status='INACTIVE'
                    WHERE e.team_id=? AND a.alert_code='NO_ELIGIBLE_SUCCESSOR' AND a.created_at<=t.occurred_at)
                    """, f.team()));
            assertTrue(flag(c, "SELECT EXISTS(SELECT 1 FROM public.team_leadership_succession_events WHERE team_id=? AND outcome='NO_ELIGIBLE_SUCCESSOR')", f.team()));
        }
    }

    @Test
    void plannedReplacementConsumesDormantDesignationAndProtectsFinalLeader() throws SQLException {
        try (Connection c = connect()) {
            Fixture f = fixtureForScenario(c);
            UUID candidate = user(c, org(c, f), "candidate");
            UUID designation = alternate(c, f, candidate, 1);
            UUID member = id(c, "SELECT team_membership_id FROM public.team_leadership_alternates WHERE team_leadership_alternate_id=?", designation);
            transition(c, f, "ACTIVE");
            assertThrows(SQLException.class, () -> scalar(c, "SELECT public.epms_team_end_planned(?,?,?,?::uuid,?)", f.admin(), f.team(), f.assignment(), null, "No replacement"));
            scalar(c, "SELECT public.epms_team_end_planned(?,?,?,?,?)", f.admin(), f.team(), f.assignment(), member, "Planned replacement");
            assertTrue(flag(c, "SELECT public.epms_team_has_permission(?,?,'TASK_ASSIGN')", candidate, f.team()));
            assertTrue(flag(c, "SELECT revoked_at IS NOT NULL AND revoked_by_user_id=? FROM public.team_leadership_alternates WHERE team_leadership_alternate_id=?", f.admin(), designation));
            assertFalse(flag(c, "SELECT EXISTS(SELECT 1 FROM public.team_leadership_succession_events WHERE team_id=?)", f.team()));
            assertThrows(SQLException.class, () -> execute(c, """
                    UPDATE public.team_memberships SET left_at=clock_timestamp(),end_type_code='ADMINISTRATIVE',
                    removal_reason_code='TEAM_REASSIGNMENT',removed_by_user_id=?,removal_comment='Bypass'
                    WHERE team_membership_id=?
                    """, f.admin(), member));
            assertTrue(flag(c, "SELECT public.epms_team_has_permission(?,?,'TASK_ASSIGN')", candidate, f.team()));
        }
    }

    @Test
    void runtimeAndConcurrentTransactionsCannotBypassProtectedBoundary() throws SQLException {
        try (Connection c = connect(); Connection lock = connect()) {
            Fixture f = fixtureForScenario(c);
            UUID outsider = user(c, org(c, f), "outsider");
            for (String role : new String[]{"epms_app", "epms_rbac_runtime"}) {
                assertFalse(flag(c, "SELECT has_function_privilege(?::name,'public.epms_team_transition(uuid,uuid,text,text)','EXECUTE')", role));
                assertFalse(flag(c, "SELECT has_function_privilege(?::name,'public.epms_team_has_permission(uuid,uuid,text)','EXECUTE')", role));
                assertFalse(flag(c, "SELECT has_table_privilege(?::name,'public.team_lifecycle_events','INSERT,UPDATE,DELETE,TRUNCATE')", role));
            }
            assertThrows(SQLException.class, () -> scalar(c, "SELECT public.epms_rbac_assign_role(?,?,?,?)", f.admin(), outsider, "TEAM_LEAD", "Unscoped"));
            lock.setAutoCommit(false);
            try {
                transition(lock, f, "ACTIVE");
                execute(c, "SET lock_timeout='300ms'");
                SQLException blocked = assertThrows(SQLException.class, () -> disable(c, f, "ACCOUNT_SECURITY"));
                assertEquals("55P03", blocked.getSQLState());
                lock.commit();
            } finally {
                lock.rollback();
                execute(c, "RESET lock_timeout");
            }
            assertTrue(flag(c, "SELECT is_active FROM public.teams WHERE team_id=?", f.team()));
            disable(c, f, "ACCOUNT_SECURITY");
            assertTrue(flag(c, "SELECT lifecycle_status='INACTIVE' FROM public.teams WHERE team_id=?", f.team()));
        }
    }

    @Test
    void repeatedImmediateLeadershipGrantsRetainValidScopedRbac() throws SQLException {
        try (Connection c = connect()) {
            for (int iteration = 0; iteration < 25; iteration++) {
                Fixture f = fixtureForScenario(c);
                transition(c, f, "ACTIVE");
                assertTrue(flag(c, "SELECT public.epms_team_has_permission(?,?,'TASK_ASSIGN')", f.leader(), f.team()));
                transition(c, f, "INACTIVE");
                transition(c, f, "ARCHIVED");
            }
        }
    }

    @Test
    void rejectedMissingLeadershipTargetLeavesInvariantIntact() throws SQLException {
        try (Connection c = connect()) {
            Fixture f = fixtureForScenario(c);
            transition(c, f, "ACTIVE");
            SQLException failure = assertThrows(SQLException.class, () -> scalar(c,
                    "SELECT public.epms_team_assign_permanent(?,?,?,?)",
                    f.admin(), f.team(), UUID.randomUUID(), "Missing target"));
            assertEquals("23514", failure.getSQLState());
            assertTrue(flag(c, "SELECT public.epms_team_has_eligible_leader(?)", f.team()));
            assertTrue(flag(c, """
                    SELECT EXISTS (SELECT 1 FROM public.team_leadership_assignments l
                      JOIN public.security_user_roles x ON x.assignment_id=l.rbac_assignment_id
                      WHERE l.team_leadership_assignment_id=? AND l.ended_at IS NULL
                        AND x.revoked_at IS NULL AND x.valid_from<=clock_timestamp() AND x.valid_until IS NULL)
                    """, f.assignment()));
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
