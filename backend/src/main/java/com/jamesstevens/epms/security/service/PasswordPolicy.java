package com.jamesstevens.epms.security.service;

import java.text.Normalizer;

/** Validates and normalizes passwords before hashing and verification. */
public final class PasswordPolicy {
    private static final int MIN_PASSWORD_CODE_POINTS = 15;
    private static final int MAX_PASSWORD_CODE_POINTS = 128;
    private PasswordPolicy() { }

    public static String normalizeAndValidate(String password) {
        if (password == null) {
            throw new IllegalArgumentException("Password is required");
        }
        String normalized = Normalizer.normalize(password, Normalizer.Form.NFC);
        int count = normalized.codePointCount(0, normalized.length());
        if (count < MIN_PASSWORD_CODE_POINTS || count > MAX_PASSWORD_CODE_POINTS) {
            throw new IllegalArgumentException("Password must contain 15 to 128 Unicode code points");
        }
        return normalized;
    }

    public static String normalizeForVerification(String password) {
        return password == null ? null : Normalizer.normalize(password, Normalizer.Form.NFC);
    }
}
