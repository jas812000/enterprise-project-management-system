package com.jamesstevens.epms.security.service;

import static org.junit.jupiter.api.Assertions.*;

import com.jamesstevens.epms.organization.dto.CreateOrganizationRequest;
import com.jamesstevens.epms.organization.service.OrganizationService;
import com.jamesstevens.epms.security.outage.PasswordScreeningOutageRecorder;
import com.jamesstevens.epms.user.dto.CreateUserRequest;
import com.jamesstevens.epms.user.entity.UserCredential;
import com.jamesstevens.epms.user.repository.UserCredentialRepository;
import com.jamesstevens.epms.user.service.UserService;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Tests the actual transactional issuer against PostgreSQL 18, without production secrets. */
@SpringBootTest
@Testcontainers
@Import(ProvisionalCredentialIssuerIntegrationTest.TestIssuerConfiguration.class)
class ProvisionalCredentialIssuerIntegrationTest {
    private static final String PASSWORD = "Correct horse battery staple! 2026";
    private static final UUID HUMAN_ACCOUNT_TYPE_ID =
            UUID.fromString("0199a000-0000-7000-8000-000000000001");
    private static final byte[] KEY = new byte[32];
    private static final DeferredScreeningCipher TEST_CIPHER = new DeferredScreeningCipher(KEY);

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18")
            .withInitScript("test-db-init.sql");

    @TestConfiguration(proxyBeanMethods = false)
    static class TestIssuerConfiguration {
        @Bean
        @Primary
        ProvisionalCredentialIssuer testProvisionalIssuer(UserCredentialRepository credentials,
                PasswordScreeningOutageRecorder outages) {
            return new ProvisionalCredentialIssuer(credentials, outages,
                    TEST_CIPHER, "test-key-v1", Clock.systemUTC());
        }
    }

    @Autowired private ProvisionalCredentialIssuer issuer;
    @Autowired private UserCredentialRepository credentials;
    @Autowired private OrganizationService organizations;
    @Autowired private UserService users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private record Ids(UUID organizationId, UUID userId) { }

    private Ids createUser() {
        String employee = "B14-" + UUID.randomUUID().toString().substring(0, 12);
        return new TransactionTemplate(transactionManager).execute(status -> {
            var organization = organizations.createOrganization(
                    new CreateOrganizationRequest("B14 Test Organization", "Provisional issuance test"));
            var user = users.createUser(new CreateUserRequest(null, null,
                    HUMAN_ACCOUNT_TYPE_ID, employee, "Provisional", "Tester",
                    employee.toLowerCase() + "@example.com"));
            return new Ids(organization.organizationId(), user.userId());
        });
    }

    private void cleanup(Ids ids) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            var outageIds = jdbc.queryForList(
                    "SELECT screening_outage_id FROM user_credentials WHERE user_id = ? "
                    + "AND screening_outage_id IS NOT NULL", UUID.class, ids.userId());
            jdbc.update("DELETE FROM user_credentials WHERE user_id = ?", ids.userId());
            for (UUID outageId : outageIds) {
                jdbc.update("DELETE FROM password_security_events WHERE outage_id = ?", outageId);
                jdbc.update("DELETE FROM password_screening_outages WHERE outage_id = ?", outageId);
            }
            jdbc.update("DELETE FROM users WHERE user_id = ?", ids.userId());
            jdbc.update("DELETE FROM organizations WHERE organization_id = ?", ids.organizationId());
        });
    }

    private static UserCredential candidate(UUID userId) {
        return new UserCredential(userId, "encoded-argon2id-hash", "ARGON2ID", false);
    }

    @Test
    void persistsEncryptedProvisionalCredentialAndMatchingIncidentEvent() throws Exception {
        Ids ids = createUser();
        try {
            UUID outageId = issuer.issueAfterConfirmedOutage(
                    candidate(ids.userId()), PASSWORD, "PROVIDER_TIMEOUT");
            UserCredential stored = credentials.findByUserId(ids.userId()).orElseThrow();
            assertEquals("PENDING_VERIFICATION", stored.getScreeningStatus());
            assertEquals(stored.getProvisionalIssuedAt().plusHours(12), stored.getProvisionalExpiresAt());
            assertEquals(outageId, stored.getScreeningOutageId());
            assertEquals("test-key-v1", stored.getScreeningDigestKeyId());
            byte[] ciphertext = stored.getScreeningDigestCiphertext();
            assertNotNull(ciphertext);
            String digest = HexFormat.of().withUpperCase().formatHex(
                    MessageDigest.getInstance("SHA-1").digest(PASSWORD.getBytes(StandardCharsets.UTF_8)));
            assertEquals(digest, TEST_CIPHER.decrypt(ciphertext, stored.getUserCredentialId().toString()));
            assertThrows(IllegalStateException.class,
                    () -> TEST_CIPHER.decrypt(ciphertext, UUID.randomUUID().toString()));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM password_screening_outages WHERE outage_id = ?", Integer.class, outageId));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM password_security_events "
                    + "WHERE outage_id = ? AND event_type = 'SCREENING_FAILURE'", Integer.class, outageId));
        } finally {
            cleanup(ids);
        }
    }

    @Test
    void directIssuerHoldsV20AdvisoryLockUntilTransactionCompletion() {
        Ids ids = createUser();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                issuer.issueAfterConfirmedOutage(candidate(ids.userId()), PASSWORD, "PROVIDER_TIMEOUT");
                // Separate PostgreSQL session must not acquire the same per-user lock.
                try (var other = DriverManager.getConnection(postgres.getJdbcUrl(),
                        postgres.getUsername(), postgres.getPassword());
                     var statement = other.prepareStatement(
                             "SELECT pg_catalog.pg_try_advisory_xact_lock(" +
                             "pg_catalog.hashtextextended(CAST(? AS text), 82302026))")) {
                    statement.setString(1, ids.userId().toString());
                    try (ResultSet result = statement.executeQuery()) {
                        assertTrue(result.next());
                        assertFalse(result.getBoolean(1),
                                "Direct issuer must hold the V20 lock until commit");
                    }
                } catch (java.sql.SQLException exception) {
                    throw new IllegalStateException("Advisory lock acceptance query failed", exception);
                }
            });
        } finally {
            cleanup(ids);
        }
    }

    @Test
    void outerTransactionRollbackRemovesCredentialOutageAndEvent() {
        Ids ids = createUser();
        UUID[] outage = new UUID[1];
        try {
            assertThrows(IllegalStateException.class, () ->
                    new TransactionTemplate(transactionManager).execute(status -> {
                        outage[0] = issuer.issueAfterConfirmedOutage(
                                candidate(ids.userId()), PASSWORD, "PROVIDER_TIMEOUT");
                        throw new IllegalStateException("intentional rollback");
                    }));
            assertNotNull(outage[0]);
            assertTrue(credentials.findByUserId(ids.userId()).isEmpty());
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM password_screening_outages WHERE outage_id = ?",
                    Integer.class, outage[0]));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM password_security_events WHERE outage_id = ?",
                    Integer.class, outage[0]));
        } finally {
            cleanup(ids);
        }
    }

    @Test
    void outagePersistenceFailureRollsBackPreviouslyFlushedCredential() {
        Ids ids = createUser();
        try {
            assertThrows(IllegalArgumentException.class, () ->
                    issuer.issueAfterConfirmedOutage(candidate(ids.userId()),
                            PASSWORD, "X".repeat(81)));
            assertTrue(credentials.findByUserId(ids.userId()).isEmpty());
        } finally {
            cleanup(ids);
        }
    }
}
