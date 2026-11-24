package com.jamesstevens.epms.security.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Fenced recovery: short DB transactions surround, but never include, the provider request. */
@Service
public class DeferredClaimedRecoveryService {
    private static final int SNAPSHOT_CREDENTIAL_ID_COLUMN = 1;
    private static final int SNAPSHOT_USER_ID_COLUMN = 2;
    private static final int SNAPSHOT_OUTAGE_ID_COLUMN = 3;
    private static final int SNAPSHOT_CLAIM_TOKEN_COLUMN = 4;
    private static final int CLAIM_EXPIRY_COL = 5;
    private static final int PASSWORD_CHANGED_COL = 6;
    private static final int SNAPSHOT_PASSWORD_HASH_COLUMN = 7;
    private static final int SNAPSHOT_VERSION_COLUMN = 8;
    private static final int ENCRYPTED_DIGEST_COL = 9;
    private static final int SNAPSHOT_KEY_ID_COLUMN = 10;
    private static final int STATE_CLAIM_TOKEN_INDEX = 0;
    private static final int STATE_CLAIM_EXPIRES_AT_INDEX = 1;
    private static final int STATE_SCREENING_STATUS_INDEX = 2;
    private static final int PROVISIONAL_EXPIRY_IDX = 3;
    private static final int STATE_PASSWORD_CHANGED_AT_INDEX = 4;
    private static final int STATE_PASSWORD_HASH_INDEX = 5;
    private static final int STATE_VERSION_INDEX = 6;
    private static final int STATE_RETRY_ATTEMPTS_INDEX = 7;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final DeferredCredentialScreeningService screening;
    private final Clock clock;

    private record Snapshot(UUID credentialId, UUID userId, UUID outageId, UUID token,
            OffsetDateTime claimExpiresAt, OffsetDateTime passwordChangedAt,
            String passwordHash, long version, byte[] encryptedDigest, String keyId) { }

    @Autowired
    public DeferredClaimedRecoveryService(JdbcTemplate jdbc,
            PlatformTransactionManager manager, DeferredCredentialScreeningService screening) {
        this(jdbc, new TransactionTemplate(manager), screening, Clock.systemUTC());
    }

    /* package-private: explicit transaction and clock injection for recovery tests. */
    /* default */ DeferredClaimedRecoveryService(JdbcTemplate jdbc, TransactionTemplate transactions,
            DeferredCredentialScreeningService screening, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        this.screening = Objects.requireNonNull(screening);
        this.clock = Objects.requireNonNull(clock);
    }

    public DeferredCredentialScreeningService.Outcome resolveClaimed(DeferredRecoveryClaimService.Claim claim) {
        Objects.requireNonNull(claim, "claim");
        Snapshot snapshot = transactions.execute(tx -> jdbc.query(
                "SELECT user_credential_id, user_id, screening_outage_id, screening_claim_token, "
                + "screening_claim_expires_at, password_changed_at, password_hash, version, "
                + "screening_digest_ciphertext, screening_digest_key_id FROM user_credentials "
                + "WHERE user_credential_id = ? AND screening_status = 'PENDING_VERIFICATION' "
                + "AND provisional_expires_at > ? AND screening_claim_token = ? "
                + "AND screening_claim_expires_at > ?",
                (rs, row) -> new Snapshot((UUID) rs.getObject(SNAPSHOT_CREDENTIAL_ID_COLUMN),
                        (UUID) rs.getObject(SNAPSHOT_USER_ID_COLUMN),
                        (UUID) rs.getObject(SNAPSHOT_OUTAGE_ID_COLUMN),
                                (UUID) rs.getObject(SNAPSHOT_CLAIM_TOKEN_COLUMN),
                        rs.getObject(CLAIM_EXPIRY_COL, OffsetDateTime.class),
                                rs.getObject(PASSWORD_CHANGED_COL, OffsetDateTime.class),
                        rs.getString(SNAPSHOT_PASSWORD_HASH_COLUMN), rs.getLong(SNAPSHOT_VERSION_COLUMN),
                                rs.getBytes(ENCRYPTED_DIGEST_COL), rs.getString(SNAPSHOT_KEY_ID_COLUMN)),
                claim.credentialId(), OffsetDateTime.now(clock), claim.token(), OffsetDateTime.now(clock))
                .stream().findFirst().orElse(null));
        DeferredCredentialScreeningService.Outcome outcome;
        if (snapshot == null) {
            outcome = DeferredCredentialScreeningService.Outcome.NOT_PENDING;
        } else {
            String digest = screening.decryptClaimedDigest(snapshot.encryptedDigest(), snapshot.keyId(),
                    snapshot.credentialId());
            boolean unavailable = false;
            boolean compromised = false;
            try {
                compromised = screening.screenClaimedDigest(digest)
                        == PwnedPasswordScreeningService.Result.COMPROMISED;
            } catch (PwnedPasswordScreeningService.ScreeningUnavailableException error) {
                unavailable = true;
            }
            final boolean retry = unavailable;
            final boolean found = compromised;
            outcome = transactions.execute(tx -> finalizeResult(claim, snapshot, retry, found));
        }
        return outcome;
    }

    private DeferredCredentialScreeningService.Outcome finalizeResult(
            DeferredRecoveryClaimService.Claim claim, Snapshot original,
            boolean unavailable, boolean compromised) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        // Coordinate with token issuance and password changes before taking the credential row lock.
        // PostgreSQL pg_advisory_xact_lock returns void; do not map it to Long.
        jdbc.queryForObject(
                "SELECT pg_catalog.pg_advisory_xact_lock("
                + "pg_catalog.hashtextextended(CAST(? AS text), 82302026))",
                String.class, original.userId());
        // Lock only during finalization, never during HTTP. A password change also takes this row lock.
        java.util.List<Object[]> rows = jdbc.query("SELECT screening_claim_token, screening_claim_expires_at, "
                + "screening_status, provisional_expires_at, password_changed_at, password_hash, "
                + "version, screening_retry_attempts FROM user_credentials "
                + "WHERE user_credential_id = ? FOR UPDATE",
                (rs, row) -> new Object[] {rs.getObject(STATE_CLAIM_TOKEN_INDEX + 1),
                        rs.getObject(STATE_CLAIM_EXPIRES_AT_INDEX + 1, OffsetDateTime.class),
                        rs.getString(STATE_SCREENING_STATUS_INDEX + 1),
                                rs.getObject(PROVISIONAL_EXPIRY_IDX + 1, OffsetDateTime.class),
                        rs.getObject(STATE_PASSWORD_CHANGED_AT_INDEX + 1, OffsetDateTime.class),
                                rs.getString(STATE_PASSWORD_HASH_INDEX + 1),
                        rs.getLong(STATE_VERSION_INDEX + 1), rs.getInt(STATE_RETRY_ATTEMPTS_INDEX + 1)},
                                claim.credentialId());
        DeferredCredentialScreeningService.Outcome outcome;
        if (rows.isEmpty()) {
            outcome = DeferredCredentialScreeningService.Outcome.NOT_PENDING;
        } else {
            Object[] state = rows.getFirst();
            if (!claim.token().equals(state[STATE_CLAIM_TOKEN_INDEX]) || state[STATE_CLAIM_EXPIRES_AT_INDEX] == null
                    || !((OffsetDateTime) state[STATE_CLAIM_EXPIRES_AT_INDEX]).isAfter(now)
                    || !"PENDING_VERIFICATION".equals(state[STATE_SCREENING_STATUS_INDEX])
                    || state[PROVISIONAL_EXPIRY_IDX] == null
                    || !((OffsetDateTime) state[PROVISIONAL_EXPIRY_IDX]).isAfter(now)
                    || !Objects.equals(original.passwordChangedAt(), state[STATE_PASSWORD_CHANGED_AT_INDEX])
                    || !Objects.equals(original.passwordHash(), state[STATE_PASSWORD_HASH_INDEX])
                    || original.version() != (long) state[STATE_VERSION_INDEX]) {
                outcome = DeferredCredentialScreeningService.Outcome.NOT_PENDING;
            } else if (unavailable) {
                int previousAttempts = (int) state[STATE_RETRY_ATTEMPTS_INDEX];
                OffsetDateTime expires = (OffsetDateTime) state[PROVISIONAL_EXPIRY_IDX];
                int attempts = ScreeningRetryPolicy.nextAttemptCount(previousAttempts);
                OffsetDateTime next = ScreeningRetryPolicy.nextRetryAt(now, expires, previousAttempts);
                jdbc.update("UPDATE user_credentials SET screening_retry_attempts = ?, "
                        + "next_screening_retry_at = ?, screening_claim_token = NULL, "
                        + "screening_claim_expires_at = NULL, version = version + 1 "
                        + "WHERE user_credential_id = ?", attempts, next, claim.credentialId());
                outcome = DeferredCredentialScreeningService.Outcome.UNAVAILABLE;
            } else {
                jdbc.update("UPDATE user_credentials SET screening_status = ?, must_change_password = ?, "
                        + "screening_digest_ciphertext = NULL, screening_digest_key_id = NULL, "
                        + "screening_retry_attempts = 0, next_screening_retry_at = NULL, "
                        + "screening_claim_token = NULL, screening_claim_expires_at = NULL, "
                        + "version = version + 1 WHERE user_credential_id = ?",
                        compromised ? "CHANGE_REQUIRED" : "VERIFIED", compromised, claim.credentialId());
                jdbc.update("INSERT INTO password_security_events "
                        + "(event_id, occurred_at, user_id, outage_id, event_type) "
                        + "VALUES (uuidv7(), ?, ?, ?, ?)", now, original.userId(), original.outageId(),
                        compromised ? "PASSWORD_COMPROMISED" : "PASSWORD_VERIFIED");
                outcome = compromised ? DeferredCredentialScreeningService.Outcome.CHANGE_REQUIRED
                        : DeferredCredentialScreeningService.Outcome.VERIFIED;
            }
        }
        return outcome;
    }
}
