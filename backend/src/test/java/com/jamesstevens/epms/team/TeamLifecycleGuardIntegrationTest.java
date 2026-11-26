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
class TeamLifecycleGuardIntegrationTest {
    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18")
            .withInitScript("rbac-test-db-init.sql");

    @Test
    void rejectsInvalidTransitionsAndMembershipPeriods() throws Exception {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load().migrate();
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement sql = connection.createStatement()) {
            UUID org = id(sql, "INSERT INTO public.organizations(name) VALUES ('Guard Test') RETURNING organization_id");
            UUID user = newUser(sql, org, "member");
            UUID team = id(sql, "INSERT INTO public.teams(organization_id,name) VALUES ('" + org
                    + "','Guard Team') RETURNING team_id");
            String update = "UPDATE public.teams SET ";
            String where = " WHERE team_id='" + team + "'";
            assertThrows(SQLException.class, () -> sql.execute(update + "lifecycle_status='INACTIVE'" + where));
            assertThrows(SQLException.class, () -> sql.execute(update + "lifecycle_status='ACTIVE',is_active=true" + where));
            String insert = "INSERT INTO public.team_memberships(organization_id,team_id,user_id,membership_period_number) VALUES ('"
                    + org + "','" + team + "','" + user + "',";
            assertThrows(SQLException.class, () -> sql.execute(insert + "2)"));
            UUID membership = id(sql, insert + "1) RETURNING team_membership_id");
            sql.execute("UPDATE public.team_memberships SET left_at=clock_timestamp(),end_type_code='VOLUNTARY',"
                    + "removal_reason_code='VOLUNTARY_DEPARTURE',removal_comment='Departure',removed_by_user_id='"
                    + user + "' WHERE team_membership_id='" + membership + "'");
            assertThrows(SQLException.class, () -> sql.execute(insert + "3)"));
            id(sql, insert + "2) RETURNING team_membership_id");
            assertTrue(value(sql, "SELECT NOT has_table_privilege('epms_app','public.teams','INSERT,UPDATE,DELETE')"));
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
