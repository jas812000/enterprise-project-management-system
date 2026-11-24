package com.jamesstevens.epms.security.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** Generates opaque bearer credentials; persistence receives only SHA-256 digests. */
public final class AuthenticationTokenMaterial {
    private static final int TOKEN_BYTES = 32;
    private final SecureRandom random;

    public AuthenticationTokenMaterial() {
        this(new SecureRandom());
    }

    @SuppressWarnings("PMD.CommentDefaultAccessModifier") // Package-private deterministic test seam.
    AuthenticationTokenMaterial(SecureRandom random) {
        this.random = java.util.Objects.requireNonNull(random);
    }

    public Token generate() {
        byte[] raw = new byte[TOKEN_BYTES];
        random.nextBytes(raw);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        return new Token(value, digest(raw));
    }

    public static byte[] hashEncoded(String encoded) {
        if (encoded == null || !encoded.matches("[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("Invalid token encoding");
        }
        byte[] raw;
        try {
            raw = Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid token encoding", ex);
        }
        if (raw.length != TOKEN_BYTES || !Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw).equals(encoded)) {
            throw new IllegalArgumentException("Invalid token encoding");
        }
        return digest(raw);
    }

    private static byte[] digest(byte[] raw) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(raw);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public record Token(String value, byte[] hash) {
        public Token {
            java.util.Objects.requireNonNull(value);
            hash = hash.clone();
            if (hash.length != TOKEN_BYTES) {
                throw new IllegalArgumentException("Invalid digest length");
            }
        }

        @Override
        public byte[] hash() {
            return hash.clone();
        }

        @Override
        public String toString() {
            return "Token[REDACTED]";
        }
    }
}
