package com.jamesstevens.epms.security.service;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class AuthenticationAccessValidationEffectivePrivilegeIntegrationTest {
    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18")
            .withInitScript("test-db-init.sql");

    @Test
    void applicationCanExecuteValidationButCannotReadProtectedTables() throws Exception {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword()).locations("classpath:db/migration").load().migrate();
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE epms_app");
            for (String query : new String[] {
                    "SELECT token_hash FROM public.user_access_tokens LIMIT 0",
                    "SELECT session_id FROM public.user_auth_sessions LIMIT 0"}) {
                SQLException denied = assertThrows(SQLException.class, () -> statement.executeQuery(query));
                assertEquals("42501", denied.getSQLState());
            }
            try (PreparedStatement prepared = connection.prepareStatement(
                    "SELECT public.epms_auth_validate_access(?)")) {
                prepared.setBytes(1, new byte[32]);
                try (ResultSet result = prepared.executeQuery()) {
                    assertTrue(result.next());
                    assertNull(result.getObject(1));
                }
            }
        }
    }
}
