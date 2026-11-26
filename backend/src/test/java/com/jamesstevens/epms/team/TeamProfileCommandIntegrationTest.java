package com.jamesstevens.epms.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
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
class TeamProfileCommandIntegrationTest {
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

    @Test
    void profileCommandsRequireAuthorityAndRetainImmutableHistory()
            throws SQLException, InterruptedException, ExecutionException {
        try (Connection c = connect()) {
            UUID org = id(c, """
                    INSERT INTO public.organizations(name) VALUES ('Profile test') RETURNING organization_id
                    """);
            UUID admin = user(c, org, "admin");
            UUID outsider = user(c, org, "outsider");
            execute(c, """
                    INSERT INTO public.security_user_roles(user_id,role_id)
                    SELECT ?,role_id FROM public.security_roles WHERE code='SYSTEM_ADMIN'
                    """, admin);
            TeamCommandRepository commands = new TeamCommandRepository(runtimeJdbc());
            assertThrows(DataAccessException.class,
                    () -> commands.create(outsider, "Engineering", null, false, "Denied"));
            assertThrows(DataAccessException.class,
                    () -> commands.create(admin, "Engineering", null, false, " "));
            UUID team = commands.create(admin, "Engineering", "Initial", false, "Create");
            assertNotNull(team);
            assertEquals(team, commands.profiles(admin, team, 1, 0).getFirst().teamId());
            assertEquals("Initial", commands.profiles(admin, team, 1, 0).getFirst().description());
            assertEquals(team, commands.profileConflicts(admin, null, "Enginering").getFirst().teamId());
            assertEquals(team, commands.profileConflicts(admin, null, "Engin3ering").getFirst().teamId());
            assertEquals(team, commands.profileConflicts(admin, null, "Enginearing").getFirst().teamId());
            assertEquals(team, commands.profileConflicts(admin, null, "Énginéering").getFirst().teamId());
            assertEquals(team, commands.profileConflicts(admin, null, "Еnginеering").getFirst().teamId());
            assertThrows(DataAccessException.class,
                    () -> commands.create(admin, "Enginering", null, false, "Typo"));
            assertThrows(DataAccessException.class, () -> commands.profiles(outsider, null, 10, 0));
            assertThrows(DataAccessException.class, () -> commands.profileConflicts(outsider, null, "Engineering"));
            assertThrows(DataAccessException.class, () -> commands.profiles(admin, null, 101, 0));
            assertTrue(commands.profiles(admin, UUID.randomUUID(), 1, 0).isEmpty());

            assertTrue(flag(c, """
                    SELECT lifecycle_status='PENDING_SETUP' AND NOT is_active AND version=0
                    AND created_by_user_id=? FROM public.teams WHERE team_id=?
                    """, admin, team));
            assertThrows(DataAccessException.class,
                    () -> commands.create(admin, " engineering ", null, true, "Exact duplicate"));
            assertThrows(DataAccessException.class,
                    () -> commands.create(admin, "Engin-eering", null, false, "Similar"));
            assertThrows(DataAccessException.class,
                    () -> commands.create(admin, "Engin-eering", null, true, "Unreviewed"));
            String review = commands.reviewConflicts(admin, null, "Engin-eering");
            assertThrows(DataAccessException.class,
                    () -> commands.createReviewed(admin, "Enginearing", null, "Wrong name", review));
            UUID similar = commands.createReviewed(admin, "Engin-eering", null, "Separate function", review);
            assertThrows(DataAccessException.class,
                    () -> commands.createReviewed(admin, "Engin3ering", null, "Stale review", review));
            assertTrue(flag(c, """
                    SELECT ?=ANY(similar_team_ids) AND reason='Separate function'
                    AND reviewed_name_conflicts IS NOT NULL
                    FROM public.team_operation_events WHERE team_id=? AND operation='TEAM_CREATE'
                    """, team, similar));
            assertThrows(DataAccessException.class,
                    () -> commands.updateProfile(outsider, team, 0, "Platform", null, false, "Denied"));
            assertEquals(Long.valueOf(1L),
                    commands.updateProfile(admin, team, 0, "Platform", "Updated", false, "Rename"));
            assertThrows(DataAccessException.class,
                    () -> commands.updateProfile(admin, team, 0, "Stale", null, false, "Stale"));
            assertThrows(DataAccessException.class,
                    () -> commands.updateProfile(admin, similar, 0, "Platform", null, true, "Exact"));
            assertThrows(DataAccessException.class,
                    () -> commands.updateProfile(admin, similar, 0, "Plat-form", null, false, "Similar"));
            assertThrows(DataAccessException.class,
                    () -> commands.updateProfile(admin, similar, 0, "Plat-form", null, true, " "));
            String renameReview = commands.reviewConflicts(admin, similar, "Plat-form");
            assertEquals(Long.valueOf(2L),
                    commands.updateProfile(admin, team, 1, "Platform", "New version", false, "Refresh"));
            assertThrows(DataAccessException.class, () -> commands.updateProfileReviewed(
                    admin, similar, 0, "Plat-form", null, "Stale confirmation", renameReview));
            String refreshedReview = commands.reviewConflicts(admin, similar, "Plat-form");
            assertEquals(Long.valueOf(1L), commands.updateProfileReviewed(
                    admin, similar, 0, "Plat-form", null, "Separate Team", refreshedReview));
            assertTrue(flag(c, """
                    SELECT ?=ANY(similar_team_ids) AND reason='Separate Team'
                    FROM public.team_operation_events WHERE team_id=? AND operation='TEAM_PROFILE_UPDATE'
                    """, team, similar));
            assertTrue(flag(c, """
                    SELECT profile_before->>'name'='Engineering' AND profile_after->>'name'='Platform'
                    FROM public.team_operation_events WHERE team_id=? AND operation='TEAM_PROFILE_UPDATE'
                    AND profile_before->>'name'='Engineering'
                    """, team));
            assertThrows(SQLException.class, () -> execute(c,
                    "UPDATE public.team_operation_events SET reason='Changed' WHERE team_id=?", team));
            verifyConcurrentReviews(commands, admin);
            commands.transition(admin, team, "ARCHIVED", "Archive");
            assertThrows(DataAccessException.class,
                    () -> commands.updateProfile(admin, team, 2, "Reopen", null, false, "Denied"));
        }
    }

    private static void verifyConcurrentReviews(TeamCommandRepository commands, UUID admin)
            throws InterruptedException, ExecutionException {
        commands.create(admin, "Logistics", null, false, "Create conflict base");
        String firstName = "Logis-tics";
        String secondName = "Logis_tics";
        String firstReview = commands.reviewConflicts(admin, null, firstName);
        String secondReview = commands.reviewConflicts(admin, null, secondName);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> tryReviewedCreate(commands, admin, firstName, firstReview));
            var second = workers.submit(() -> tryReviewedCreate(commands, admin, secondName, secondReview));
            assertNotEquals(first.get(), second.get(), "Only one competing reviewed proposal may succeed");
        }
    }

    @Test
    void supportedNameWarningExamplesAndNegativeControl() throws SQLException {
        try (Connection c = connect()) {
            for (String candidate : new String[]{"Engin-eering", "Enginering", "Enginееring"}) {
                assertTrue(flag(c, "SELECT public.epms_team_names_similar(?,?)", "Engineering", candidate));
            }
            assertTrue(flag(c, "SELECT public.epms_team_names_similar(?,?)", "Cafe Operations", "Café Operations"));
            assertFalse(flag(c, "SELECT public.epms_team_names_similar(?,?)", "Engineering", "Finance"));
        }
    }

    private static boolean tryReviewedCreate(TeamCommandRepository commands, UUID admin, String name, String review) {
        boolean created = true;
        try {
            commands.createReviewed(admin, name, null, "Concurrent review", review);
        } catch (DataAccessException exception) {
            assertTrue(exception.getMostSpecificCause() instanceof SQLException);
            assertEquals("23514", ((SQLException) exception.getMostSpecificCause()).getSQLState());
            created = false;
        }
        return created;
    }

    @Test
    void runtimePrivilegesRemainRestricted() throws SQLException {
        try (Connection c = connect()) {
            assertTrue(flag(c, "SELECT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version='34' AND success)"));
            for (String signature : new String[]{"public.epms_team_command_create(uuid,text,text,boolean,text)",
                    "public.epms_team_command_update_profile(uuid,uuid,bigint,text,text,boolean,text)",
                    "public.epms_team_command_profiles(uuid,uuid,integer,integer)",
                    "public.epms_team_command_profile_conflicts(uuid,uuid,text)",
                    "public.epms_team_command_review_conflicts(uuid,uuid,text)",
                    "public.epms_team_command_create_reviewed(uuid,text,text,boolean,text,text)",
                    "public.epms_team_command_update_profile_reviewed(uuid,uuid,bigint,text,text,boolean,text,text)"}) {
                assertTrue(flag(c, "SELECT has_function_privilege('epms_rbac_runtime',?, 'EXECUTE')", signature));
                assertFalse(flag(c, "SELECT has_function_privilege('epms_app',?, 'EXECUTE')", signature));
                assertFalse(flag(c, """
                        SELECT EXISTS (SELECT 1 FROM pg_proc p CROSS JOIN LATERAL aclexplode(p.proacl) a
                        WHERE p.oid=to_regprocedure(?) AND a.grantee=0 AND a.privilege_type='EXECUTE')
                        """, signature));
            }
            assertFalse(flag(c, """
                    SELECT has_function_privilege('epms_rbac_runtime',
                    'public.epms_team_require_profile_admin(uuid,text)','EXECUTE')
                    """));
            assertFalse(flag(c, """
                    SELECT has_function_privilege('epms_rbac_runtime',
                    'public.epms_team_profile_conflicts(uuid,uuid,text)','EXECUTE')
                    """));
            for (String table : new String[]{"public.teams", "public.team_operation_events"}) {
                assertFalse(flag(c, """
                        SELECT has_table_privilege('epms_rbac_runtime',?, 'INSERT,UPDATE,DELETE,TRUNCATE')
                        """, table));
            }
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
