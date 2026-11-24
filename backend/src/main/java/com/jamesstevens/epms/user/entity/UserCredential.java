package com.jamesstevens.epms.user.entity;

import com.jamesstevens.epms.common.persistence.PostgresUuidV7;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * JPA persistence entity for a user's password credentials.
 *
 * <p>Maps to user_credentials, introduced by Flyway V3.
 * Password credentials are stored separately from user
 * profiles and must never be exposed through ordinary
 * user response DTOs.</p>
 *
 * <p>Authentication, password encoding, account lockout,
 * and authorization are managed by security services.</p>
 */
@Entity
@Table(name = "user_credentials")
public class UserCredential {
    private static final String NULL_STATE_CLEARING = "PMD.NullAssignment";
    private static final String SCREENING_PENDING = "PENDING_VERIFICATION";
    private static final int MIN_DIGEST_BYTES = 28;
    private static final int PROVISIONAL_EXPIRY_HOURS = 12;

    @Id
    @PostgresUuidV7
    @Column(
            name = "user_credential_id",
            nullable = false,
            updatable = false
    )
    private UUID userCredentialId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "password_hash", nullable = false, length = 500)
    private String passwordHash;

    @Column(
            name = "password_algorithm_code",
            nullable = false,
            length = 50
    )
    private String passwordAlgorithmCode;

    @Column(name = "password_changed_at", nullable = false)
    private OffsetDateTime passwordChangedAt;


    @Column(name = "screening_status", nullable = false, length = 32)
    private String screeningStatus = "VERIFIED";

    @Column(name = "provisional_issued_at")
    private OffsetDateTime provisionalIssuedAt;

    @Column(name = "provisional_expires_at")
    private OffsetDateTime provisionalExpiresAt;

    @Column(name = "screening_digest_ciphertext")
    private byte[] screeningDigestCiphertext;

    @Column(name = "screening_digest_key_id", length = 100)
    private String screeningDigestKeyId;

    @Column(name = "screening_retry_attempts", nullable = false)
    private int screeningRetryAttempts;

    @Column(name = "next_screening_retry_at")
    private OffsetDateTime nextScreeningRetryAt;

    @Column(name = "screening_outage_id")
    private UUID screeningOutageId;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @Column(name = "login_lockout_stage", nullable = false)
    private short loginLockoutStage;

    @Column(name = "login_stage_failures", nullable = false)
    private short loginStageFailures;

    @Column(name = "login_locked_until")
    private OffsetDateTime loginLockedUntil;

    @Column(name = "login_admin_unlock_required", nullable = false)
    private boolean loginAdminUnlockRequired;

    @Column(name = "failed_attempt_count", nullable = false)
    private int failedAttemptCount;

    @Column(name = "last_failed_attempt_at")
    private OffsetDateTime lastFailedAttemptAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /**
     * Approved outage retry intervals: 1, 2, 4, 8, 15, 30, 60, 120, 240 minutes.
     * Nine retries maximum; no tenth retry. The provisional deadline is absolute.
     */
    public void deferScreeningRetry(OffsetDateTime now) {
        java.util.Objects.requireNonNull(now, "now");
        if (!SCREENING_PENDING.equals(screeningStatus)) {
            throw new IllegalStateException("Credential is not pending screening");
        }
        if (provisionalExpiresAt == null || !now.isBefore(provisionalExpiresAt)) {
            throw new IllegalStateException("Provisional screening window has expired");
        }
        nextScreeningRetryAt = com.jamesstevens.epms.security.service.ScreeningRetryPolicy
                .nextRetryAt(now, provisionalExpiresAt, screeningRetryAttempts);
        screeningRetryAttempts = com.jamesstevens.epms.security.service.ScreeningRetryPolicy
                .nextAttemptCount(screeningRetryAttempts);
    }

    public int getScreeningRetryAttempts() { return screeningRetryAttempts; }
    public OffsetDateTime getNextScreeningRetryAt() { return nextScreeningRetryAt; }


    /** Marks a newly issued provisional credential; callers must encrypt the digest first. */
    public void issueProvisionalPassword(OffsetDateTime issuedAt, byte[] ciphertext,
                                         String keyId, UUID outageId) {
        java.util.Objects.requireNonNull(issuedAt, "issuedAt");
        java.util.Objects.requireNonNull(ciphertext, "ciphertext");
        if (ciphertext.length < MIN_DIGEST_BYTES
                || keyId == null || keyId.isBlank() || outageId == null) {
            throw new IllegalArgumentException("Incomplete provisional screening metadata");
        }
        screeningStatus = SCREENING_PENDING;
        provisionalIssuedAt = issuedAt;
        provisionalExpiresAt = provisionalExpiration(issuedAt);
        screeningDigestCiphertext = ciphertext.clone();
        screeningDigestKeyId = keyId;
        screeningOutageId = outageId;
        screeningRetryAttempts = 0;
        nextScreeningRetryAt = issuedAt;
    }

    /** Applies only to the exact password generation that was screened. */
    // Null denotes absent/cleared credential state; preserve persistence semantics.
    @SuppressWarnings(NULL_STATE_CLEARING)
    public boolean completeScreening(OffsetDateTime expectedPasswordChangedAt,
                                     boolean compromised) {
        boolean eligible = SCREENING_PENDING.equals(screeningStatus)
                && java.util.Objects.equals(passwordChangedAt, expectedPasswordChangedAt);
        if (eligible) {
            screeningStatus = compromised ? "CHANGE_REQUIRED" : "VERIFIED";
            if (compromised) {
                mustChangePassword = true;
            }
            clearDeferredScreeningMaterial();
            nextScreeningRetryAt = null;
        }
        return eligible;
    }

    /** Expiration never extends a provisional password or resets it automatically. */
    // Null denotes absent/cleared credential state; preserve persistence semantics.
    @SuppressWarnings(NULL_STATE_CLEARING)
    public boolean expireProvisionalPassword(OffsetDateTime now) {
        java.util.Objects.requireNonNull(now, "now");
        boolean expired = SCREENING_PENDING.equals(screeningStatus)
                && provisionalExpiresAt != null && !now.isBefore(provisionalExpiresAt);
        if (expired) {
            screeningStatus = "EXPIRED";
            nextScreeningRetryAt = null;
            mustChangePassword = true;
            clearDeferredScreeningMaterial();
        }
        return expired;
    }

    // Null denotes absent/cleared credential state; preserve persistence semantics.
    @SuppressWarnings(NULL_STATE_CLEARING)
    private void clearDeferredScreeningMaterial() {
        if (screeningDigestCiphertext != null) {
            java.util.Arrays.fill(screeningDigestCiphertext, (byte) 0);
        }
        screeningDigestCiphertext = null;
        screeningDigestKeyId = null;
    }

    /** Null means no deferred ciphertext is retained; empty bytes are not equivalent. */
    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
    public byte[] getScreeningDigestCiphertext() {
        return screeningDigestCiphertext == null ? null : screeningDigestCiphertext.clone();
    }

    public String getScreeningDigestKeyId() { return screeningDigestKeyId; }
    public UUID getScreeningOutageId() { return screeningOutageId; }
    public OffsetDateTime getProvisionalIssuedAt() { return provisionalIssuedAt; }

    public String getScreeningStatus() {
        return screeningStatus;
    }

    public OffsetDateTime getProvisionalExpiresAt() {
        return provisionalExpiresAt;
    }

    public static OffsetDateTime provisionalExpiration(OffsetDateTime issuedAt) {
        return java.util.Objects.requireNonNull(issuedAt).plusHours(PROVISIONAL_EXPIRY_HOURS);
    }

    public com.jamesstevens.epms.security.service.ProgressiveLoginLockoutPolicy.State
            getLoginLockoutState() {
        return new com.jamesstevens.epms.security.service.ProgressiveLoginLockoutPolicy.State(
                loginLockoutStage, loginStageFailures,
                loginLockedUntil == null ? null : loginLockedUntil.toInstant(),
                loginAdminUnlockRequired);
    }

    /** Applies a validated policy transition; callers must enforce authorization and audit. */
    // Null denotes absent/cleared credential state; preserve persistence semantics.
    @SuppressWarnings(NULL_STATE_CLEARING)
    public void applyLoginLockoutState(
            com.jamesstevens.epms.security.service.ProgressiveLoginLockoutPolicy.State state) {
        java.util.Objects.requireNonNull(state, "state");
        loginLockoutStage = (short) state.stage();
        loginStageFailures = (short) state.failuresInStage();
        loginLockedUntil = state.lockedUntil() == null ? null
                : OffsetDateTime.ofInstant(state.lockedUntil(), ZoneOffset.UTC);
        loginAdminUnlockRequired = state.administratorUnlockRequired();
    }

    /**
     * Required by JPA.
     */
    protected UserCredential() {
        // Required by JPA.
    }

    /**
     * Creates a credential record using an already encoded password.
     *
     * @param userId owning user identifier
     * @param passwordHash encoded password
     * @param passwordAlgorithmCode password encoding identifier
     * @param mustChangePassword whether a password change is required
     */
    public UserCredential(
            UUID userId,
            String passwordHash,
            String passwordAlgorithmCode,
            boolean mustChangePassword) {

        this.userId = java.util.Objects.requireNonNull(userId);
        this.passwordHash = requireNonblank(passwordHash);
        this.passwordAlgorithmCode =
                requireNonblank(passwordAlgorithmCode);
        this.mustChangePassword = mustChangePassword;
        this.failedAttemptCount = 0;
    }

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        createdAt = now;
        updatedAt = now;

        if (passwordChangedAt == null) {
            passwordChangedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Replaces the encoded password and resets failure tracking.
     */
    // Null denotes absent/cleared credential state; preserve persistence semantics.
    @SuppressWarnings(NULL_STATE_CLEARING)
    public void changePassword(
            String newPasswordHash,
            String newPasswordAlgorithmCode) {

        passwordHash = requireNonblank(newPasswordHash);
        passwordAlgorithmCode =
                requireNonblank(newPasswordAlgorithmCode);

        passwordChangedAt = OffsetDateTime.now(ZoneOffset.UTC);
        clearDeferredScreeningMaterial();
        screeningStatus = "VERIFIED";
        provisionalIssuedAt = null;
        provisionalExpiresAt = null;
        screeningOutageId = null;
        screeningRetryAttempts = 0;
        nextScreeningRetryAt = null;
        mustChangePassword = false;
        failedAttemptCount = 0;
    }

    /**
     * Records a failed authentication attempt.
     */
    /** Changes only lockout state; existing failed-attempt metadata is retained. */
    public void registerLoginFailure(java.time.Instant now) {
        com.jamesstevens.epms.security.service.ProgressiveLoginLockoutPolicy.State previous =
                getLoginLockoutState();
        if (com.jamesstevens.epms.security.service.ProgressiveLoginLockoutPolicy.isBlocked(previous, now)) {
            return;
        }
        applyLoginLockoutState(
                com.jamesstevens.epms.security.service.ProgressiveLoginLockoutPolicy.failure(previous, now));
        recordFailedAttempt();
    }

    public void resetLoginLockoutAfterSuccess(java.time.Instant now) {
        applyLoginLockoutState(
                com.jamesstevens.epms.security.service.ProgressiveLoginLockoutPolicy.successfulAuthentication(
                        getLoginLockoutState(), now));
        resetFailedAttempts();
    }

    public void administratorUnlockLogin() {
        applyLoginLockoutState(
                com.jamesstevens.epms.security.service.ProgressiveLoginLockoutPolicy.administratorUnlock());
        resetFailedAttempts();
    }

    public void recordFailedAttempt() {
        failedAttemptCount = Math.incrementExact(failedAttemptCount);
        lastFailedAttemptAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Resets the consecutive failed-attempt counter.
     *
     * <p>The last failure timestamp is retained as
     * historical information.</p>
     */
    public void resetFailedAttempts() {
        failedAttemptCount = 0;
    }

    public void requirePasswordChange() {
        mustChangePassword = true;
    }

    public void clearPasswordChangeRequirement() {
        mustChangePassword = false;
    }

    private static String requireNonblank(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Credential value must not be blank"
            );
        }
        return value;
    }

    public UUID getUserCredentialId() {
        return userCredentialId;
    }

    public UUID getUserId() {
        return userId;
    }

    /**
     * Returns the encoded password for trusted authentication services.
     */
    public String getPasswordHash() {
        return passwordHash;
    }

    public String getPasswordAlgorithmCode() {
        return passwordAlgorithmCode;
    }

    public OffsetDateTime getPasswordChangedAt() {
        return passwordChangedAt;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public int getFailedAttemptCount() {
        return failedAttemptCount;
    }

    public OffsetDateTime getLastFailedAttemptAt() {
        return lastFailedAttemptAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}
