package com.jamesstevens.epms.security.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL claim primitives. The coordinator is not wired to claims until B18D. */
@Service
public class DeferredRecoveryClaimService {
    private static final int MAX_RECOVERY_BATCH_SIZE = 100;
    private static final Duration LEASE = Duration.ofMinutes(2);
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public record Claim(UUID credentialId, UUID token) { }

    @Autowired
    public DeferredRecoveryClaimService(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    /* package-private: injectable clock for deterministic recovery tests. */
    /* default */ DeferredRecoveryClaimService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Claims eligible rows atomically, using SKIP LOCKED for concurrent callers. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Claim> claim(int limit) {
        if (limit < 1 || limit > MAX_RECOVERY_BATCH_SIZE) {
            throw new IllegalArgumentException("Recovery claim limit must be between 1 and 100");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        UUID token = UUID.randomUUID();
        String sql = "WITH candidates AS ("
                + " SELECT user_credential_id FROM user_credentials"
                + " WHERE screening_status = 'PENDING_VERIFICATION'"
                + " AND (next_screening_retry_at IS NULL OR next_screening_retry_at <= ?)"
                + " AND provisional_expires_at > ?"
                + " AND (screening_claim_expires_at IS NULL OR screening_claim_expires_at <= ?)"
                + " ORDER BY next_screening_retry_at, provisional_issued_at, user_credential_id"
                + " FOR UPDATE SKIP LOCKED LIMIT ?)"
                + " UPDATE user_credentials c SET screening_claim_token = ?,"
                + " screening_claim_expires_at = ?"
                + " FROM candidates WHERE c.user_credential_id = candidates.user_credential_id"
                + " RETURNING c.user_credential_id";
        List<UUID> credentialIds = jdbc.queryForList(sql, UUID.class,
                now, now, now, limit, token, now.plus(LEASE));
        List<Claim> claims = new ArrayList<>(credentialIds.size());
        for (UUID credentialId : credentialIds) {
            claims.add(new Claim(credentialId, token));
        }
        return List.copyOf(claims);
    }

    /** Token fencing: a stale owner cannot clear a newer owner's claim. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean release(Claim claim) {
        Objects.requireNonNull(claim, "claim");
        int updated = jdbc.update("UPDATE user_credentials SET screening_claim_token = NULL,"
                + " screening_claim_expires_at = NULL WHERE user_credential_id = ?"
                + " AND screening_claim_token = ?", claim.credentialId(), claim.token());
        return updated == 1;
    }
}
