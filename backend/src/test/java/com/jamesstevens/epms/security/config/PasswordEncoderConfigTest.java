package com.jamesstevens.epms.security.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;

class PasswordEncoderConfigTest {

    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        passwordEncoder =
                new PasswordEncoderConfig().passwordEncoder();
    }

    @Test
    void encodesPasswordUsingArgon2id() {
        String password = "SecureTestPassword123!";

        String encoded = passwordEncoder.encode(password);

        assertNotNull(encoded);
        assertNotEquals(password, encoded);
        assertTrue(encoded.startsWith("$argon2id$"));
        assertTrue(encoded.contains("m=19456,t=2,p=1"));
    }

    @Test
    void matchesCorrectPassword() {
        String password = "SecureTestPassword123!";
        String encoded = passwordEncoder.encode(password);

        assertTrue(passwordEncoder.matches(password, encoded));
    }

    @Test
    void rejectsIncorrectPassword() {
        String encoded =
                passwordEncoder.encode("CorrectPassword123!");

        assertFalse(passwordEncoder.matches(
                "IncorrectPassword123!",
                encoded
        ));
    }

    @Test
    void generatesDifferentHashesForSamePassword() {
        String password = "SecureTestPassword123!";

        String first = passwordEncoder.encode(password);
        String second = passwordEncoder.encode(password);

        assertNotEquals(first, second);
        assertTrue(passwordEncoder.matches(password, first));
        assertTrue(passwordEncoder.matches(password, second));
    }

    @Test
    void supportsMultilingualPasswords() {
        String password = "Montañaภาษาไทย日本語🔐2026";

        String encoded = passwordEncoder.encode(password);

        assertTrue(encoded.startsWith("$argon2id$"));
        assertTrue(passwordEncoder.matches(password, encoded));
    }
}
