package com.jamesstevens.epms.security.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PasswordPolicyTest {
    @Test void rejectsTooShort() {
        assertThrows(IllegalArgumentException.class,
                () -> PasswordPolicy.normalizeAndValidate("short"));
    }
    @Test void rejectsTooLong() {
        assertThrows(IllegalArgumentException.class,
                () -> PasswordPolicy.normalizeAndValidate("x".repeat(129)));
    }
    @Test void acceptsUnicodeCodePoints() {
        assertEquals(15, PasswordPolicy.normalizeAndValidate("🔐".repeat(15))
                .codePointCount(0, "🔐".repeat(15).length()));
    }
    @Test void normalizesNfc() {
        String decomposed = "e\u0301" + "x".repeat(14);
        assertEquals("\u00e9" + "x".repeat(14),
                PasswordPolicy.normalizeAndValidate(decomposed));
    }
}