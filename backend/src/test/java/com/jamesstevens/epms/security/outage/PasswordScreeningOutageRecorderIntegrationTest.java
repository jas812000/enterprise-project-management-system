package com.jamesstevens.epms.security.outage;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
class PasswordScreeningOutageRecorderIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18")
            .withInitScript("test-db-init.sql");

    @Autowired private PasswordScreeningOutageRecorder recorder;
    @Autowired private PasswordScreeningOutageRepository outages;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void persistsIncidentAndSecurityEventWithSameOutageId() {
        UUID id = recorder.recordFailure("PROVIDER_TIMEOUT");
        try {
            assertTrue(outages.findById(id).isPresent());
            Integer events = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM password_security_events "
                    + "WHERE outage_id = ? AND event_type = 'SCREENING_FAILURE'",
                    Integer.class, id);
            assertEquals(1, events);
        } finally {
            jdbc.update("DELETE FROM password_security_events WHERE outage_id = ?", id);
            outages.deleteById(id);
        }
    }

    @Test
    void rollsBackIncidentAndEventTogether() {
        UUID[] captured = new UUID[1];
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class, () -> tx.execute(status -> {
            captured[0] = recorder.recordFailure("PROVIDER_TIMEOUT");
            throw new IllegalStateException("intentional rollback");
        }));
        assertNotNull(captured[0]);
        assertFalse(outages.existsById(captured[0]));
        Integer events = jdbc.queryForObject(
                "SELECT COUNT(*) FROM password_security_events WHERE outage_id = ?",
                Integer.class, captured[0]);
        assertEquals(0, events);
    }
}
