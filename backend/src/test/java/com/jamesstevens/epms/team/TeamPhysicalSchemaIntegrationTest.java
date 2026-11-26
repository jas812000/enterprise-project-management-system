package com.jamesstevens.epms.team;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real PostgreSQL 18 verification of Phase 12 schema and privilege isolation. */
@Testcontainers
class TeamPhysicalSchemaIntegrationTest {
    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18")
            .withInitScript("rbac-test-db-init.sql");

    @Test
    void lifecycleMembershipAndDormantAlternateDatabaseGuarantees() throws Exception {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement sql = connection.createStatement()) {
            assertTrue(value(sql, "SELECT EXISTS(SELECT 1 FROM flyway_schema_history "
                    + "WHERE version='27' AND success)"));
            assertTrue(value(sql, "SELECT NOT has_table_privilege('epms_app', "
                    + "'public.team_leadership_alternates','SELECT,INSERT,UPDATE,DELETE')"));
            assertTrue(value(sql, "SELECT NOT has_table_privilege('epms_rbac_runtime', "
                    + "'public.team_leadership_assignments','INSERT,UPDATE,DELETE')"));
            assertTrue(value(sql, "SELECT NOT has_table_privilege('epms_app', "
                    + "'public.team_leadership_succession_events','INSERT,UPDATE,DELETE')"));
            assertTrue(value(sql, "SELECT (SELECT count(*) FROM public.security_role_permissions)=35"));
            assertTrue(value(sql, "SELECT NOT EXISTS (SELECT 1 FROM public.security_user_roles)"));
            UUID org = id(sql, "INSERT INTO public.organizations(name) "
                    + "VALUES ('Team Schema Test') RETURNING organization_id");
            UUID actor = newUser(sql, org, "actor");
            UUID leader = newUser(sql, org, "leader");
            UUID alternate = newUser(sql, org, "alternate");
            UUID third = newUser(sql, org, "third");
            UUID teamA = id(sql, "INSERT INTO public.teams(organization_id,name) VALUES ('"
                    + org + "','Alpha Team') RETURNING team_id");
            UUID teamB = id(sql, "INSERT INTO public.teams(organization_id,name) VALUES ('"
                    + org + "','Beta Team') RETURNING team_id");
            assertTrue(value(sql, "SELECT lifecycle_status='PENDING_SETUP' AND NOT is_active "
                    + "FROM public.teams WHERE team_id='" + teamA + "'"));
            assertThrows(SQLException.class, () -> sql.execute("INSERT INTO public.teams(organization_id,name) "
                    + "VALUES ('" + org + "',' alpha   team ' )"));
            UUID teamC = id(sql, "INSERT INTO public.teams(organization_id,name) VALUES ('"
                    + org + "','Archived Team') RETURNING team_id");
            assertThrows(SQLException.class, () -> sql.execute("UPDATE public.teams SET lifecycle_status='ARCHIVED',is_archived=true, "
                    + "archived_at=clock_timestamp(),archive_reason='Cancelled during setup' "
                    + "WHERE team_id='" + teamC + "'"));
            sql.execute("INSERT INTO public.security_user_roles(user_id,role_id) SELECT '" + actor
                    + "',role_id FROM public.security_roles WHERE code='SYSTEM_ADMIN'");
            id(sql, "SELECT public.epms_team_transition('" + actor + "','" + teamC
                    + "','ARCHIVED','Cancelled during setup')");
            assertThrows(SQLException.class, () -> sql.execute("UPDATE public.teams "
                    + "SET name='Renamed' WHERE team_id='" + teamC + "'"));

            UUID membership = id(sql, "INSERT INTO public.team_memberships("
                    + "organization_id,team_id,user_id,membership_period_number) VALUES ('"
                    + org + "','" + teamA + "','" + leader + "',1) RETURNING team_membership_id");
            assertThrows(SQLException.class, () -> sql.execute("INSERT INTO public.team_memberships("
                    + "organization_id,team_id,user_id,membership_period_number) VALUES ('"
                    + org + "','" + teamA + "','" + leader + "',2)"));
            assertThrows(SQLException.class, () -> sql.execute("UPDATE public.team_memberships SET "
                    + "left_at=clock_timestamp(),end_type_code='ADMINISTRATIVE', "
                    + "removal_reason_code='TEAM_REASSIGNMENT',removed_by_user_id='" + actor + "' "
                    + "WHERE team_membership_id='" + membership + "'"));
            sql.execute("UPDATE public.team_memberships SET "
                    + "left_at=clock_timestamp(),end_type_code='ADMINISTRATIVE', "
                    + "removal_reason_code='TEAM_REASSIGNMENT',removal_comment='Reassigned', "
                    + "removed_by_user_id='" + actor + "' WHERE team_membership_id='" + membership + "'");
            assertThrows(SQLException.class, () -> sql.execute("UPDATE public.team_memberships SET "
                    + "removal_comment='Tampered' WHERE team_membership_id='" + membership + "'"));

            UUID alternateMembership = id(sql, "INSERT INTO public.team_memberships("
                    + "organization_id,team_id,user_id,membership_period_number) VALUES ('"
                    + org + "','" + teamA + "','" + alternate + "',1) RETURNING team_membership_id");
            UUID thirdMembership = id(sql, "INSERT INTO public.team_memberships("
                    + "organization_id,team_id,user_id,membership_period_number) VALUES ('"
                    + org + "','" + teamA + "','" + third + "',1) RETURNING team_membership_id");
            id(sql, "INSERT INTO public.team_leadership_alternates("
                    + "team_membership_id,organization_id,team_id,user_id,priority,designated_by_user_id) VALUES ('"
                    + alternateMembership + "','"
                    + org + "','" + teamA + "','" + alternate + "',1,'" + actor
                    + "') RETURNING team_leadership_alternate_id");
            id(sql, "INSERT INTO public.team_leadership_alternates("
                    + "team_membership_id,organization_id,team_id,user_id,priority,designated_by_user_id) VALUES ('"
                    + thirdMembership + "','" + org + "','" + teamA + "','" + third + "',2,'" + actor
                    + "') RETURNING team_leadership_alternate_id");
            assertThrows(SQLException.class, () -> sql.execute("INSERT INTO public.team_leadership_alternates("
                    + "team_membership_id,organization_id,team_id,user_id,priority,designated_by_user_id) VALUES ('"
                    + alternateMembership + "','" + org + "','" + teamB + "','" + alternate + "',1,'" + actor + "')"));
            assertThrows(SQLException.class, () -> sql.execute("INSERT INTO public.team_leadership_alternates("
                    + "team_membership_id,organization_id,team_id,user_id,priority,designated_by_user_id) VALUES ('"
                    + membership + "','" + org + "','" + teamA + "','" + leader + "',3,'" + actor + "')"));
            assertTrue(value(sql, "SELECT NOT EXISTS (SELECT 1 FROM public.security_user_roles "
                    + "WHERE user_id='" + alternate + "')"));
        }
    }

    private static UUID newUser(Statement statement, UUID org, String prefix) throws SQLException {
        return id(statement, "INSERT INTO public.users(organization_id,account_type_id,"
                + "first_name,last_name,email) SELECT '" + org + "',account_type_id,'"
                + prefix + "','Test','" + prefix + UUID.randomUUID()
                + "@example.invalid' FROM public.account_types WHERE code='HUMAN' RETURNING user_id");
    }

    private static UUID id(Statement statement, String sql) throws SQLException {
        try (ResultSet rs = statement.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getObject(1, UUID.class);
        }
    }

    private static boolean value(Statement statement, String sql) throws SQLException {
        try (ResultSet rs = statement.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getBoolean(1);
        }
    }
}
