package com.jamesstevens.epms.security.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Prepares a password-screening outcome without persisting plaintext or hashes. */
public final class CredentialScreeningPreparation {
    private static final int PROVISIONAL_EXPIRY_HOURS = 12;
    private final PasswordScreeningDecisionService decisions;
    private final DeferredScreeningCipher cipher;
    private final String keyId;

    public enum Status { VERIFIED, PROVISIONAL }
    public record Prepared(Status status, OffsetDateTime issuedAt, OffsetDateTime expiresAt,
                           byte[] encryptedDigest, String keyId, UUID outageId) {
        public Prepared {
            Objects.requireNonNull(status);
            if (status == Status.PROVISIONAL) {
                Objects.requireNonNull(issuedAt);
                Objects.requireNonNull(expiresAt);
                Objects.requireNonNull(encryptedDigest);
                Objects.requireNonNull(keyId);
                Objects.requireNonNull(outageId);
                encryptedDigest = encryptedDigest.clone();
            } else if (issuedAt != null || expiresAt != null || encryptedDigest != null
                    || keyId != null || outageId != null) {
                throw new IllegalArgumentException("Verified credential must not retain deferred metadata");
            }
        }
        /** Null denotes the absence of encrypted screening material for VERIFIED status. */
        @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
        @Override public byte[] encryptedDigest() {
            return encryptedDigest == null ? null : encryptedDigest.clone();
        }
    }

    public CredentialScreeningPreparation(PasswordScreeningDecisionService decisions,
                                          DeferredScreeningCipher cipher, String keyId) {
        this.decisions = Objects.requireNonNull(decisions);
        this.cipher = Objects.requireNonNull(cipher);
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalArgumentException("Key ID required");
        }
        this.keyId = keyId;
    }

    /** Caller supplies an existing credential-scoped AAD identifier and a persisted outage ID. */
    public Prepared prepare(String password, String credentialId, UUID outageId, OffsetDateTime now) {
        Objects.requireNonNull(credentialId);
        Objects.requireNonNull(now);
        String normalized = PasswordPolicy.normalizeAndValidate(password);
        PasswordScreeningDecisionService.Decision decision = decisions.decide(normalized);
        if (decision == PasswordScreeningDecisionService.Decision.REJECT_COMPROMISED) {
            throw new IllegalArgumentException("Password is compromised");
        }
        Prepared prepared;
        if (decision == PasswordScreeningDecisionService.Decision.VERIFIED) {
            prepared = new Prepared(Status.VERIFIED, null, null, null, null, null);
        } else {
            Objects.requireNonNull(outageId, "Persisted outage ID required for provisional issuance");
            try {
                String digest = HexFormat.of().withUpperCase().formatHex(
                        MessageDigest.getInstance("SHA-1")
                                .digest(normalized.getBytes(StandardCharsets.UTF_8)));
                byte[] encrypted = cipher.encrypt(digest, credentialId);
                prepared = new Prepared(Status.PROVISIONAL, now,
                        now.plusHours(PROVISIONAL_EXPIRY_HOURS), encrypted, keyId, outageId);
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-1 unavailable", exception);
            }
        }
        return prepared;
    }
}
