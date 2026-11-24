package com.jamesstevens.epms.security.service;

import com.jamesstevens.epms.security.outage.PasswordScreeningOutageRecorder;
import com.jamesstevens.epms.user.entity.UserCredential;
import com.jamesstevens.epms.user.repository.UserCredentialRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Atomic provisional metadata issuance after a caller has classified a provider outage. */
@Service
public class ProvisionalCredentialIssuer {
    @PersistenceContext
    private EntityManager entityManager;

    private final UserCredentialRepository credentials;
    private final PasswordScreeningOutageRecorder outages;
    private final DeferredScreeningCipher cipher;
    private final String keyId;
    private final Clock clock;

    @Autowired
    public ProvisionalCredentialIssuer(UserCredentialRepository credentials,
                                       PasswordScreeningOutageRecorder outages) {
        this(credentials, outages, null, null, Clock.systemUTC());
    }

    /* package-private: supports dependency injection in tests. */
    /* package-private: injectable dependencies for deterministic issuance tests. */
    /* default */ ProvisionalCredentialIssuer(UserCredentialRepository credentials,
                                PasswordScreeningOutageRecorder outages,
                                DeferredScreeningCipher cipher, String keyId, Clock clock) {
        this.credentials = Objects.requireNonNull(credentials);
        this.outages = Objects.requireNonNull(outages);
        this.cipher = cipher;
        this.keyId = keyId;
        this.clock = Objects.requireNonNull(clock);
    }

    /** Only call after a PROVISIONAL_REQUIRED screening decision; never as a fallback for compromised passwords. */
    @Transactional
    public UUID issueAfterConfirmedOutage(UserCredential credential, String normalizedPassword,
                                           String failureCategory) {
        Objects.requireNonNull(credential);
        String password = PasswordPolicy.normalizeAndValidate(normalizedPassword);
        if (failureCategory == null || failureCategory.isBlank()) {
            throw new IllegalArgumentException("Non-sensitive outage category required");
        }
        DeferredScreeningCipher encryption = cipher != null ? cipher : DeferredScreeningCipher.fromEnvironment();
        String encryptionKeyId = keyId != null ? keyId : System.getenv("EPMS_SCREENING_ENCRYPTION_KEY_ID");
        if (encryptionKeyId == null || encryptionKeyId.isBlank()) {
            throw new IllegalStateException("Missing screening encryption key identifier");
        }
        // Same transaction-scoped lock and seed as public.epms_auth_issue (V20).
        // The issuer is callable independently of UserCredentialService.
        UUID userId = Objects.requireNonNull(credential.getUserId(), "User ID missing");
        entityManager.createNativeQuery(
                "SELECT pg_catalog.pg_advisory_xact_lock(" +
                "pg_catalog.hashtextextended(CAST(:userId AS text), 82302026))")
                .setParameter("userId", userId.toString())
                .getSingleResult();
        UserCredential saved = credentials.saveAndFlush(credential);
        UUID credentialId = Objects.requireNonNull(saved.getUserCredentialId(), "Credential ID missing");
        UUID outageId = outages.recordFailure(failureCategory);
        try {
            String digest = HexFormat.of().withUpperCase().formatHex(
                    MessageDigest.getInstance("SHA-1").digest(password.getBytes(StandardCharsets.UTF_8)));
            byte[] encrypted = encryption.encrypt(digest, credentialId.toString());
            saved.issueProvisionalPassword(OffsetDateTime.now(clock), encrypted, encryptionKeyId, outageId);
            credentials.saveAndFlush(saved);
            return outageId;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-1 unavailable", impossible);
        }
    }
}
