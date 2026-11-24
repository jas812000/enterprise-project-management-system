package com.jamesstevens.epms.security.bootstrap;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Restricted, one-time administrator provisioning. No ordinary application datasource. */
public final class InitialAdminProvisioner {
    private static final int FIRST_NAME_PARAMETER = 1;
    private static final int LAST_NAME_PARAMETER = 2;
    private static final int EMAIL_PARAMETER = 3;
    private static final int PASSWORD_HASH_PARAMETER = 4;

    private final BootstrapPasswordPreflight preflight;
    private final PasswordEncoder encoder;

    public InitialAdminProvisioner(BootstrapPasswordPreflight preflight, PasswordEncoder encoder) {
        this.preflight = Objects.requireNonNull(preflight);
        this.encoder = Objects.requireNonNull(encoder);
    }

    public UUID provision(Connection connection, String first, String last, String email,
            String password) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        if (connection.getAutoCommit()) {
            throw new IllegalStateException("Bootstrap requires an explicit transaction");
        }
        String verified = preflight.requireVerified(password);
        String encoded = encoder.encode(verified);
        if (encoded == null || !encoded.startsWith("$argon2id$")) {
            throw new IllegalStateException("Bootstrap requires Argon2id encoding");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT public.epms_bootstrap_initial_admin(?, ?, ?, ?)")) {
            statement.setString(FIRST_NAME_PARAMETER, first);
            statement.setString(LAST_NAME_PARAMETER, last);
            statement.setString(EMAIL_PARAMETER, email);
            statement.setString(PASSWORD_HASH_PARAMETER, encoded);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Bootstrap did not return an administrator identifier");
                }
                return (UUID) result.getObject(1);
            }
        }
    }
}
