package com.jamesstevens.epms.security.service;

import com.jamesstevens.epms.user.entity.UserCredential;
import com.jamesstevens.epms.user.repository.UserCredentialRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Resolves one pending credential without holding a transaction during provider access. */
@Service
public class DeferredCredentialScreeningService {
    private final UserCredentialRepository credentials;
    private final PwnedPasswordScreeningService screening;
    private final JdbcTemplate jdbc;
    private final DeferredScreeningCipher cipher;
    private final String keyId;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public enum Outcome { VERIFIED, CHANGE_REQUIRED, NOT_PENDING, UNAVAILABLE }

    private record Snapshot(UUID credentialId, UUID userId, UUID outageId,
            OffsetDateTime generation, String passwordHash, Long version,
            byte[] encryptedDigest, String keyId) { }

    @Autowired
    public DeferredCredentialScreeningService(UserCredentialRepository credentials, JdbcTemplate jdbc,
            PlatformTransactionManager manager) {
        this(credentials, PwnedPasswordScreeningService.withHttpClient(), jdbc,
                null, null, Clock.systemUTC(), new TransactionTemplate(manager));
    }

    /* package-private: deterministic dependencies and explicit transaction ownership. */
    @SuppressWarnings("PMD.CommentDefaultAccessModifier") // Test-only dependency injection seam.
    DeferredCredentialScreeningService(UserCredentialRepository credentials,
            PwnedPasswordScreeningService screening, JdbcTemplate jdbc,
            DeferredScreeningCipher cipher, String keyId, Clock clock,
            TransactionTemplate transactions) {
        this.credentials = Objects.requireNonNull(credentials);
        this.screening = Objects.requireNonNull(screening);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.cipher = cipher;
        this.keyId = keyId;
        this.clock = Objects.requireNonNull(clock);
        this.transactions = Objects.requireNonNull(transactions);
    }

    /* package-private: claimed-screening integration. */
    @SuppressWarnings("PMD.CommentDefaultAccessModifier") // Package-private screening integration.
    PwnedPasswordScreeningService.Result screenClaimedDigest(String digest) {
        return screening.screenDigest(digest);
    }

    /* package-private: claimed-screening integration. */
    @SuppressWarnings("PMD.CommentDefaultAccessModifier") // Package-private screening integration.
    String decryptClaimedDigest(byte[] encrypted, String expectedKeyId, UUID credentialId) {
        String configured = keyId != null ? keyId : System.getenv("EPMS_SCREENING_ENCRYPTION_KEY_ID");
        if (configured == null || !configured.equals(expectedKeyId)) {
            throw new IllegalStateException("Screening encryption key identifier mismatch");
        }
        if (encrypted == null) {
            throw new IllegalStateException("Missing encrypted screening digest");
        }
        DeferredScreeningCipher decryptor = cipher != null ? cipher : DeferredScreeningCipher.fromEnvironment();
        return decryptor.decrypt(encrypted, credentialId.toString());
    }

    /** The caller must not own a transaction; provider access is always outside one. */
    @SuppressWarnings("PMD.OnlyOneReturn") // Nonpending credentials fail closed before provider access.
    public Outcome resolve(UUID credentialId) {
        Objects.requireNonNull(credentialId, "credentialId");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Deferred screening requires a nontransactional caller");
        }
        Snapshot snapshot = transactions.execute(status -> {
            UserCredential credential = credentials.findById(credentialId).orElseThrow(
                    () -> new IllegalArgumentException("Credential not found"));
            if (!"PENDING_VERIFICATION".equals(credential.getScreeningStatus())) {
                return null;
            }
            byte[] encrypted = credential.getScreeningDigestCiphertext();
            return new Snapshot(credentialId, credential.getUserId(), credential.getScreeningOutageId(),
                    credential.getPasswordChangedAt(), credential.getPasswordHash(), credential.getVersion(),
                    encrypted == null ? null : Arrays.copyOf(encrypted, encrypted.length),
                    credential.getScreeningDigestKeyId());
        });
        if (snapshot == null) {
            return Outcome.NOT_PENDING;
        }
        String digest = decryptClaimedDigest(snapshot.encryptedDigest(), snapshot.keyId(), credentialId);
        boolean unavailable = false;
        boolean compromised = false;
        try {
            compromised = screening.screenDigest(digest) == PwnedPasswordScreeningService.Result.COMPROMISED;
        } catch (PwnedPasswordScreeningService.ScreeningUnavailableException error) {
            unavailable = true;
        }
        final boolean retry = unavailable;
        final boolean breached = compromised;
        return transactions.execute(status -> finalizeResult(snapshot, retry, breached));
    }

    @SuppressWarnings("PMD.OnlyOneReturn") // Preserve locked-row stale-state and retry guards.
    private Outcome finalizeResult(Snapshot snapshot, boolean unavailable, boolean compromised) {
        // Same advisory-lock key as V20 token issuance and credential mutation.
        jdbc.queryForObject("SELECT pg_catalog.pg_advisory_xact_lock("
                + "pg_catalog.hashtextextended(CAST(? AS text), 82302026))",
                String.class, snapshot.userId());
        UserCredential credential = credentials.findByUserIdForUpdate(snapshot.userId()).orElse(null);
        if (credential == null || !snapshot.credentialId().equals(credential.getUserCredentialId())
                || !"PENDING_VERIFICATION".equals(credential.getScreeningStatus())
                || !Objects.equals(snapshot.generation(), credential.getPasswordChangedAt())
                || !Objects.equals(snapshot.passwordHash(), credential.getPasswordHash())
                || !Objects.equals(snapshot.version(), credential.getVersion())
                || !Objects.equals(snapshot.outageId(), credential.getScreeningOutageId())) {
            return Outcome.NOT_PENDING;
        }
        if (unavailable) {
            credential.deferScreeningRetry(OffsetDateTime.now(clock));
            credentials.saveAndFlush(credential);
            return Outcome.UNAVAILABLE;
        }
        if (!credential.completeScreening(snapshot.generation(), compromised)) {
            return Outcome.NOT_PENDING;
        }
        credentials.saveAndFlush(credential);
        jdbc.update("INSERT INTO password_security_events "
                + "(event_id, occurred_at, user_id, outage_id, event_type) "
                + "VALUES (uuidv7(), ?, ?, ?, ?)",
                OffsetDateTime.now(clock), credential.getUserId(), credential.getScreeningOutageId(),
                compromised ? "PASSWORD_COMPROMISED" : "PASSWORD_VERIFIED");
        return compromised ? Outcome.CHANGE_REQUIRED : Outcome.VERIFIED;
    }
}
