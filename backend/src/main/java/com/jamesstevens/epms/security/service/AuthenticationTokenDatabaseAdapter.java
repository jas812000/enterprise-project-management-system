package com.jamesstevens.epms.security.service;

import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Restricted gateway for the protected PostgreSQL token issuance function. */
@Repository
public class AuthenticationTokenDatabaseAdapter {
    private static final int SHA256_DIGEST_BYTES = 32;
    private final JdbcTemplate jdbc;

    public AuthenticationTokenDatabaseAdapter(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    /** Accepts a SHA-256 access-token digest, never a raw bearer token. Returns null when rejected. */
    public UUID validateAccessDigest(byte[] accessHash) {
        Objects.requireNonNull(accessHash, "accessHash");
        if (accessHash.length != SHA256_DIGEST_BYTES) {
            throw new IllegalArgumentException("Access token digest must be 32 bytes");
        }
        return jdbc.queryForObject("SELECT public.epms_auth_validate_access(?)",
                UUID.class, accessHash.clone());
    }

    /** Internal database operation only. Never expose userId as a client-selected identity. */
    public UUID issueAuthenticated(UUID userId, byte[] accessHash, byte[] renewalHash) {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(accessHash);
        Objects.requireNonNull(renewalHash);
        if (accessHash.length != SHA256_DIGEST_BYTES || renewalHash.length != SHA256_DIGEST_BYTES
                || java.security.MessageDigest.isEqual(accessHash, renewalHash)) {
            throw new IllegalArgumentException("Invalid token digests");
        }
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT public.epms_auth_issue(?, ?, ?)", UUID.class,
                userId, accessHash.clone(), renewalHash.clone()));
    }
}
