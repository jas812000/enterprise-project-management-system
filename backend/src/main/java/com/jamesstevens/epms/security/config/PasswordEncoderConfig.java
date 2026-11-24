package com.jamesstevens.epms.security.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.jamesstevens.epms.security.service.PasswordPolicy;

/**
 * Configures Argon2id password hashing for EPMS.
 *
 * <p>Parameters follow the OWASP minimum configuration.
 * Performance must be benchmarked before production deployment.
 */
@Configuration
public class PasswordEncoderConfig {

    private static final int SALT_LENGTH = 16;
    private static final int HASH_LENGTH = 32;
    private static final int PARALLELISM = 1;
    private static final int MEMORY_KIB = 19_456;
    private static final int ITERATIONS = 2;

    @Bean
    public PasswordEncoder passwordEncoder() {
        Argon2PasswordEncoder delegate = new Argon2PasswordEncoder(
                SALT_LENGTH, HASH_LENGTH, PARALLELISM, MEMORY_KIB, ITERATIONS);
        return new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                if (rawPassword == null) {
                    throw new IllegalArgumentException("rawPassword must not be null");
                }
                return delegate.encode(PasswordPolicy.normalizeAndValidate(rawPassword.toString()));
            }
            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                return rawPassword != null && delegate.matches(
                        PasswordPolicy.normalizeForVerification(rawPassword.toString()), encodedPassword);
            }
        };
    }
}
