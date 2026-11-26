package com.jamesstevens.epms.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jamesstevens.epms.team.entity.TeamLeadershipReadEntity;
import com.jamesstevens.epms.team.entity.TeamLeadershipType;
import com.jamesstevens.epms.team.entity.TeamLifecycleStatus;
import com.jamesstevens.epms.team.entity.TeamMembershipReadEntity;
import com.jamesstevens.epms.team.entity.TeamReadEntity;
import com.jamesstevens.epms.team.repository.TeamLeadershipReadRepository;
import com.jamesstevens.epms.team.repository.TeamMembershipReadRepository;
import com.jamesstevens.epms.team.repository.TeamReadRepository;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** V32 operational read boundary in an isolated PostgreSQL 18 container. */
@Testcontainers
class TeamPersistenceBoundaryIntegrationTest {
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
    void applicationCanReadOperationalProjectionsButCannotWrite() throws SQLException {
        try (Connection c = connect()) {
            Fixture f = fixtureForScenario(c);
            execute(c, "SET ROLE epms_app");
            try {
                assertTrue(flag(c, "SELECT EXISTS (SELECT 1 FROM public.team_persistence_teams WHERE team_id=?)", f.team()));
                assertTrue(flag(c, "SELECT EXISTS (SELECT 1 FROM public.team_persistence_memberships WHERE team_id=? AND user_id=?)", f.team(), f.leader()));
                assertTrue(flag(c, "SELECT EXISTS (SELECT 1 FROM public.team_persistence_leadership WHERE team_id=? AND user_id=?)", f.team(), f.leader()));
                SQLException denied = assertThrows(SQLException.class, () -> execute(c,
                        "UPDATE public.team_persistence_teams SET name='Forbidden' WHERE team_id=?", f.team()));
                assertEquals("42501", denied.getSQLState());
                assertEquals("42501", assertThrows(SQLException.class, () -> execute(c,
                        "DELETE FROM public.team_persistence_memberships WHERE team_id=?", f.team())).getSQLState());
                assertEquals("42501", assertThrows(SQLException.class, () -> execute(c,
                        "SELECT public.epms_team_transition(?,?,?,?)", f.admin(), f.team(), "ACTIVE", "Denied")).getSQLState());
            } finally {
                execute(c, "RESET ROLE");
            }
        }
    }

    @Test
    void confidentialTablesAndOtherRuntimeRemainClosed() throws SQLException {
        try (Connection c = connect()) {
            assertTrue(flag(c, "SELECT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version='32' AND success)"));
            for (String table : new String[]{"teams", "team_memberships", "team_leadership_assignments",
                    "team_leadership_alternates", "team_operation_events", "team_security_alerts",
                    "team_account_lifecycle_events", "team_lifecycle_events", "team_leadership_succession_events"}) {
                assertFalse(flag(c, "SELECT has_table_privilege('epms_app',?,'SELECT,INSERT,UPDATE,DELETE,TRUNCATE')", "public." + table));
            }
            execute(c, "SET ROLE epms_rbac_runtime");
            try {
                assertEquals("42501", assertThrows(SQLException.class, () -> execute(c,
                        "SELECT * FROM public.team_persistence_teams")).getSQLState());
            } finally {
                execute(c, "RESET ROLE");
            }
        }
    }

    @Test
    void closedPeriodsDisappearFromOperationalViews() throws SQLException {
        try (Connection c = connect()) {
            Fixture f = fixtureForScenario(c);
            id(c, "SELECT public.epms_team_transition(?,?,?,?)", f.admin(), f.team(), "ARCHIVED", "Archive pending team");
            execute(c, "SET ROLE epms_app");
            try {
                assertTrue(flag(c, "SELECT is_archived FROM public.team_persistence_teams WHERE team_id=?", f.team()));
                assertFalse(flag(c, "SELECT EXISTS (SELECT 1 FROM public.team_persistence_memberships WHERE team_id=?)", f.team()));
                assertFalse(flag(c, "SELECT EXISTS (SELECT 1 FROM public.team_persistence_leadership WHERE team_id=?)", f.team()));
            } finally {
                execute(c, "RESET ROLE");
            }
        }
    }

    @Test
    void hibernateMapsReadViewsUsingRestrictedApplicationLogin() throws SQLException {
        Fixture f;
        String password = UUID.randomUUID().toString();
        try (Connection c = connect()) {
            f = fixtureForScenario(c);
            // Isolated container only; production role provisioning is unchanged.
            execute(c, "ALTER ROLE epms_app LOGIN PASSWORD '" + password + "'");
        }
        Configuration configuration = new Configuration()
                .addAnnotatedClass(TeamReadEntity.class)
                .addAnnotatedClass(TeamMembershipReadEntity.class)
                .addAnnotatedClass(TeamLeadershipReadEntity.class)
                .setProperty("hibernate.connection.url", postgres.getJdbcUrl())
                .setProperty("hibernate.connection.username", "epms_app")
                .setProperty("hibernate.connection.password", password)
                .setProperty("hibernate.hbm2ddl.auto", "validate")
                .setProperty("hibernate.hbm2ddl.extra_physical_table_types", "VIEW");
        try (SessionFactory factory = configuration.buildSessionFactory();
             Session session = factory.openSession()) {
            session.setDefaultReadOnly(true);
            Transaction transaction = session.beginTransaction();
            try {
                TeamReadEntity team = session.find(TeamReadEntity.class, f.team());
                assertNotNull(team);
                assertEquals(f.team(), team.getTeamId());
                assertEquals(TeamLifecycleStatus.PENDING_SETUP, team.getLifecycleStatus());
                assertFalse(team.isActive());
                assertFalse(team.isArchived());
                assertNotNull(team.getCreatedAt());
                assertNotNull(team.getUpdatedAt());
                TeamLeadershipReadEntity leader = session.find(TeamLeadershipReadEntity.class, f.assignment());
                assertNotNull(leader);
                assertEquals(TeamLeadershipType.PERMANENT, leader.getAssignmentType());
                assertEquals(f.leader(), leader.getUserId());
                assertEquals(f.team(), leader.getTeamId());
                assertNotNull(leader.getAssignedAt());
                TeamMembershipReadEntity member = session.find(TeamMembershipReadEntity.class, leader.getTeamMembershipId());
                assertNotNull(member);
                assertEquals(f.leader(), member.getUserId());
                assertEquals(f.team(), member.getTeamId());
                assertEquals(1, member.getMembershipPeriodNumber());
                assertNotNull(member.getJoinedAt());
                assertEquals(team.getOrganizationId(), member.getOrganizationId());
                assertEquals(team.getOrganizationId(), leader.getOrganizationId());
                assertTrue(session.isReadOnly(team));
                assertTrue(session.isReadOnly(member));
                assertTrue(session.isReadOnly(leader));
                transaction.commit();
            } finally {
                if (transaction.isActive()) {
                    transaction.rollback();
                }
            }
        } finally {
            try (Connection c = connect()) {
                execute(c, "ALTER ROLE epms_app NOLOGIN PASSWORD NULL");
            }
        }
    }

    @Test
    void springDataRepositoriesScopeAndPageOperationalReads() throws SQLException {
        Fixture first;
        Fixture second;
        String password = UUID.randomUUID().toString();
        try (Connection c = connect()) {
            first = fixtureForScenario(c);
            second = fixtureForScenario(c);
            execute(c, "ALTER ROLE epms_app LOGIN PASSWORD '" + password + "'");
        }
        Configuration configuration = new Configuration()
                .addAnnotatedClass(TeamReadEntity.class)
                .addAnnotatedClass(TeamMembershipReadEntity.class)
                .addAnnotatedClass(TeamLeadershipReadEntity.class)
                .setProperty("hibernate.connection.url", postgres.getJdbcUrl())
                .setProperty("hibernate.connection.username", "epms_app")
                .setProperty("hibernate.connection.password", password)
                .setProperty("hibernate.hbm2ddl.auto", "validate")
                .setProperty("hibernate.hbm2ddl.extra_physical_table_types", "VIEW");
        try (SessionFactory factory = configuration.buildSessionFactory();
             Session session = factory.openSession()) {
            session.setDefaultReadOnly(true);
            Transaction transaction = session.beginTransaction();
            try {
                JpaRepositoryFactory repositories = new JpaRepositoryFactory(session);
                TeamReadRepository teams = repositories.getRepository(TeamReadRepository.class);
                TeamMembershipReadRepository members = repositories.getRepository(TeamMembershipReadRepository.class);
                TeamLeadershipReadRepository leaders = repositories.getRepository(TeamLeadershipReadRepository.class);
                UUID org = session.find(TeamReadEntity.class, first.team()).getOrganizationId();
                UUID missing = UUID.randomUUID();
                var page = PageRequest.of(0, 1);
                assertEquals(first.team(), teams.findByTeamIdAndOrganizationId(first.team(), org).orElseThrow().getTeamId());
                assertTrue(teams.findByTeamIdAndOrganizationId(first.team(), missing).isEmpty());
                assertTrue(teams.findByTeamIdAndOrganizationId(missing, org).isEmpty());
                assertEquals(1, teams.findByOrganizationIdOrderByNameAscTeamIdAsc(org, page).getNumberOfElements());
                assertTrue(teams.findByOrganizationIdOrderByNameAscTeamIdAsc(org, page).getTotalElements() >= 2);
                assertTrue(teams.findByOrganizationIdAndLifecycleStatusOrderByNameAscTeamIdAsc(
                        org, TeamLifecycleStatus.PENDING_SETUP, page).getTotalElements() >= 2);
                assertTrue(teams.findByOrganizationIdOrderByNameAscTeamIdAsc(missing, page).isEmpty());
                assertEquals(first.leader(), members.findByOrganizationIdAndTeamIdOrderByJoinedAtAscTeamMembershipIdAsc(
                        org, first.team(), page).getContent().getFirst().getUserId());
                assertEquals(first.team(), members.findByOrganizationIdAndUserIdOrderByJoinedAtAscTeamMembershipIdAsc(
                        org, first.leader(), page).getContent().getFirst().getTeamId());
                assertTrue(members.existsByOrganizationIdAndTeamIdAndUserId(org, first.team(), first.leader()));
                assertFalse(members.existsByOrganizationIdAndTeamIdAndUserId(org, second.team(), first.leader()));
                assertFalse(members.existsByOrganizationIdAndTeamIdAndUserId(missing, first.team(), first.leader()));
                assertEquals(first.assignment(), leaders.findByOrganizationIdAndTeamIdOrderByAssignedAtAscTeamLeadershipAssignmentIdAsc(
                        org, first.team(), page).getContent().getFirst().getTeamLeadershipAssignmentId());
                assertEquals(first.team(), leaders.findByOrganizationIdAndUserIdOrderByAssignedAtAscTeamLeadershipAssignmentIdAsc(
                        org, first.leader(), page).getContent().getFirst().getTeamId());
                assertTrue(leaders.existsByOrganizationIdAndTeamIdAndUserId(org, first.team(), first.leader()));
                assertFalse(leaders.existsByOrganizationIdAndTeamIdAndUserId(org, second.team(), first.leader()));
                assertFalse(leaders.existsByOrganizationIdAndTeamIdAndUserId(missing, first.team(), first.leader()));
                transaction.commit();
            } finally {
                if (transaction.isActive()) {
                    transaction.rollback();
                }
            }
        } finally {
            try (Connection c = connect()) {
                execute(c, "ALTER ROLE epms_app NOLOGIN PASSWORD NULL");
            }
        }
    }

    @Test
    void repositoryInterfacesExposeNoMutationMethods() {
        for (Class<?> repository : new Class<?>[]{TeamReadRepository.class,
                TeamMembershipReadRepository.class, TeamLeadershipReadRepository.class}) {
            assertFalse(org.springframework.data.repository.CrudRepository.class.isAssignableFrom(repository));
            for (var method : repository.getMethods()) {
                assertTrue(method.getName().startsWith("findBy") || method.getName().startsWith("existsBy"));
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
