package com.jamesstevens.epms.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jamesstevens.epms.team.repository.TeamCommandRepository;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class TeamCommandBoundaryIntegrationTest {
    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18")
            .withInitScript("rbac-test-db-init.sql");

    private static String runtimePassword;

    @BeforeAll
    static void migrate() throws SQLException {
        Flyway flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load();
        flyway.migrate();
        flyway.validate();
        runtimePassword = UUID.randomUUID().toString();
        try (Connection c = connect()) {
            execute(c, "ALTER ROLE epms_rbac_runtime PASSWORD '" + runtimePassword + "'");
        }
    }

    private static JdbcTemplate runtimeJdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),
                "epms_rbac_runtime", runtimePassword));
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

    private static UUID join(Connection c, UUID admin, UUID team, UUID user) throws SQLException {
        return id(c, "SELECT public.epms_team_join_member(?,?,?,?)", admin, team, user, "Join");
    }

    @Test
    void commandAllowlistDoesNotExposeTablesOrInternalFunctions() throws SQLException {
        try (Connection c = connect()) {
            assertTrue(flag(c, "SELECT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version='33' AND success)"));
            assertEquals("epms_rbac_runtime", runtimeJdbc().queryForObject("SELECT current_user", String.class));
            for (String signature : new String[]{"public.epms_team_command_join_member(uuid,uuid,uuid,text)",
                    "public.epms_team_command_leave_member(uuid,uuid,uuid,text,text,text)",
                    "public.epms_team_command_assign_permanent(uuid,uuid,uuid,text)",
                    "public.epms_team_command_end_planned(uuid,uuid,uuid,uuid,text)",
                    "public.epms_team_command_designate_alternate(uuid,uuid,uuid,smallint,text)",
                    "public.epms_team_command_revoke_alternate(uuid,uuid,uuid,text)",
                    "public.epms_team_command_transition(uuid,uuid,text,text)",
                    "public.epms_team_command_disable_account(uuid,uuid,text,text)",
                    "public.epms_team_command_has_permission(uuid,uuid,text)",
                    "public.epms_team_command_emergency_leadership(uuid,uuid,uuid,text,text)"}) {
                assertTrue(flag(c, "SELECT has_function_privilege('epms_rbac_runtime',?, 'EXECUTE')", signature));
                assertFalse(flag(c, "SELECT has_function_privilege('epms_app',?, 'EXECUTE')", signature));
                assertFalse(flag(c, "SELECT EXISTS (SELECT 1 FROM pg_proc p CROSS JOIN LATERAL aclexplode(p.proacl) a WHERE p.oid=to_regprocedure(?) AND a.grantee=0 AND a.privilege_type='EXECUTE')", signature));
            }
            for (String signature : new String[]{"public.epms_team_transition(uuid,uuid,text,text)",
                    "public.epms_team_record_event(uuid,uuid,uuid,uuid,text,text)",
                    "public.epms_team_open_leadership(uuid,uuid,uuid,text)",
                    "public.epms_team_close_leadership(uuid,uuid)",
                    "public.epms_team_disable_account(uuid,uuid,text,text)",
                    "public.epms_team_emergency_succession(uuid,uuid,uuid,text,text)"}) {
                assertFalse(flag(c, "SELECT has_function_privilege('epms_rbac_runtime',?, 'EXECUTE')", signature));
            }
            for (String table : new String[]{"teams", "team_memberships", "team_leadership_assignments",
                    "team_leadership_alternates", "team_operation_events", "team_security_alerts",
                    "security_user_roles", "team_account_lifecycle_events"}) {
                assertFalse(flag(c, "SELECT has_table_privilege('epms_rbac_runtime',?, 'INSERT,UPDATE,DELETE,TRUNCATE')", "public." + table));
            }
        }
    }

    @Test
    void restrictedRepositoryCommandsPreserveAuthorizationAndLifecycle() throws SQLException {
        try (Connection c = connect()) {
            Fixture f = fixtureForScenario(c);
            UUID org = id(c, "SELECT organization_id FROM public.teams WHERE team_id=?", f.team());
            UUID candidate = user(c, org, "candidate");
            UUID outsider = user(c, org, "outsider");
            TeamCommandRepository commands = new TeamCommandRepository(runtimeJdbc());
            assertThrows(DataAccessException.class, () -> commands.joinMember(outsider, f.team(), candidate, "Denied"));
            UUID membership = commands.joinMember(f.admin(), f.team(), candidate, "Join");
            UUID alternate = commands.designateAlternate(f.admin(), f.team(), membership, (short) 1, "Ready");
            assertFalse(commands.hasPermission(candidate, f.team(), "TASK_ASSIGN"));
            commands.revokeAlternate(f.admin(), f.team(), alternate, "Withdraw");
            commands.designateAlternate(f.admin(), f.team(), membership, (short) 1, "Ready again");
            assertNotNull(commands.transition(f.admin(), f.team(), "ACTIVE", "Activate"));
            assertTrue(commands.hasPermission(f.leader(), f.team(), "TASK_ASSIGN"));
            assertThrows(DataAccessException.class, () -> commands.emergencyLeadership(f.admin(), f.team(), f.assignment(), "ACCOUNT_SECURITY", "Wrong route"));
            assertThrows(DataAccessException.class, () -> commands.disableAccount(outsider, f.leader(), "ACCOUNT_SECURITY", "Denied"));
            assertNotNull(commands.disableAccount(f.admin(), f.leader(), "ACCOUNT_SECURITY", "Emergency"));
            assertTrue(commands.hasPermission(candidate, f.team(), "TASK_ASSIGN"));
            assertFalse(commands.hasPermission(f.leader(), f.team(), "TASK_ASSIGN"));
            assertTrue(flag(c, "SELECT is_active FROM public.teams WHERE team_id=?", f.team()));
            assertFalse(flag(c, "SELECT is_active FROM public.users WHERE user_id=?", f.leader()));
        }
    }

    @Test
    void plannedReplacementAndMembershipRemovalUseFixedCommands() throws SQLException {
        try (Connection c = connect()) {
            Fixture f = fixtureForScenario(c);
            UUID org = id(c, "SELECT organization_id FROM public.teams WHERE team_id=?", f.team());
            UUID replacement = user(c, org, "replacement");
            UUID other = user(c, org, "other");
            TeamCommandRepository commands = new TeamCommandRepository(runtimeJdbc());
            UUID member = commands.joinMember(f.admin(), f.team(), replacement, "Join");
            UUID otherMember = commands.joinMember(f.admin(), f.team(), other, "Join");
            UUID otherAssignment = commands.assignPermanent(f.admin(), f.team(), otherMember, "Co-lead");
            commands.transition(f.admin(), f.team(), "ACTIVE", "Activate");
            commands.endPlanned(f.admin(), f.team(), f.assignment(), member, "Replace");
            assertTrue(commands.hasPermission(replacement, f.team(), "TASK_ASSIGN"));
            assertFalse(commands.hasPermission(f.leader(), f.team(), "TASK_ASSIGN"));
            commands.endPlanned(f.admin(), f.team(), otherAssignment, null, "End co-lead");
            commands.leaveMember(f.admin(), f.team(), otherMember, "ADMINISTRATIVE", "ADMINISTRATIVE_DECISION", "Depart");
            assertTrue(flag(c, "SELECT left_at IS NOT NULL FROM public.team_memberships WHERE team_membership_id=?", otherMember));
            assertThrows(DataAccessException.class, () -> commands.transition(other, f.team(), "INACTIVE", "Denied"));
            commands.transition(f.admin(), f.team(), "INACTIVE", "Deactivate");
            commands.transition(f.admin(), f.team(), "ARCHIVED", "Archive");
            assertTrue(flag(c, "SELECT is_archived FROM public.teams WHERE team_id=?", f.team()));
        }
    }

    @Test
    void emergencyRelationshipClassificationPreservesRestrictedBoundary() throws SQLException {
        try (Connection c = connect(); Connection runtime = DriverManager.getConnection(
                postgres.getJdbcUrl(), "epms_rbac_runtime", runtimePassword)) {
            Fixture f = fixtureForScenario(c);
            UUID org = id(c, "SELECT organization_id FROM public.teams WHERE team_id=?", f.team());
            Fixture other = fixture(c, org);
            TeamCommandRepository commands = new TeamCommandRepository(runtimeJdbc());
            commands.transition(f.admin(), f.team(), "ACTIVE", "Activate");
            commands.transition(other.admin(), other.team(), "ACTIVE", "Activate");
            UUID candidate = user(c, org, "emergency-replacement");
            UUID membership = commands.joinMember(f.admin(), f.team(), candidate, "Join");
            commands.endPlanned(f.admin(), f.team(), f.assignment(), membership, "Replace");
            UUID missing = UUID.randomUUID();
            String before = emergencyState(c, f.team());
            for (UUID target : new UUID[]{missing, f.assignment(), other.assignment()}) {
                assertEmergencyState(runtime, "23514", f.admin(), f.team(), target);
                assertEmergencyState(runtime, "42501", f.leader(), f.team(), target);
            }
            assertEquals(before, emergencyState(c, f.team()));
            assertTrue(commands.hasPermission(candidate, f.team(), "TASK_ASSIGN"));
            assertTrue(commands.hasPermission(other.leader(), other.team(), "TASK_ASSIGN"));
            assertTrue(flag(c, "SELECT is_active FROM public.teams WHERE team_id=?", f.team()));
        }
    }

    private static void assertEmergencyState(Connection runtime, String state, UUID actor, UUID team,
            UUID assignment) {
        SQLException failure = assertThrows(SQLException.class, () -> scalar(runtime,
                "SELECT public.epms_team_command_emergency_leadership(?,?,?,?,?)",
                actor, team, assignment, "EMERGENCY_UNAVAILABILITY", "Review"));
        assertEquals(state, failure.getSQLState());
    }

    private static String emergencyState(Connection c, UUID team) throws SQLException {
        return (String) scalar(c, """
                SELECT jsonb_build_object(
                 'leaders',(SELECT jsonb_agg(to_jsonb(l) ORDER BY team_leadership_assignment_id)
                   FROM public.team_leadership_assignments l WHERE team_id=?),
                 'events',(SELECT count(*) FROM public.team_operation_events WHERE team_id=?),
                 'succession',(SELECT count(*) FROM public.team_leadership_succession_events WHERE team_id=?),
                 'team',(SELECT to_jsonb(t) FROM public.teams t WHERE team_id=?))::text
                """, team, team, team, team);
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
