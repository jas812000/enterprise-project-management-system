package com.jamesstevens.epms.security.bootstrap;

import com.jamesstevens.epms.security.service.PasswordScreeningDecisionService;
import com.jamesstevens.epms.security.service.PwnedPasswordScreeningService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InitialAdminProvisionerTest {
    private static BootstrapPasswordPreflight preflight(boolean compromised) {
        return new BootstrapPasswordPreflight(new PasswordScreeningDecisionService(
                new PwnedPasswordScreeningService(prefix -> {
                    if (compromised) {
                        throw new IllegalStateException("provider unavailable");
                    }
                    return "00000000000000000000000000000000000:1";
                })));
    }

    @Test void rejectsScreeningOutageBeforeDatabaseWrite() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(false);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        InitialAdminProvisioner provisioner = new InitialAdminProvisioner(preflight(true), encoder);
        assertThrows(IllegalArgumentException.class, () -> provisioner.provision(connection,
                "First", "Last", "admin@example.test", "a-long-password-12345"));
        verify(connection, never()).prepareStatement(anyString());
        verifyNoInteractions(encoder);
    }

    @Test void refusesAutocommitBeforePasswordProcessing() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        InitialAdminProvisioner provisioner = new InitialAdminProvisioner(preflight(false), encoder);
        assertThrows(IllegalStateException.class, () -> provisioner.provision(connection,
                "First", "Last", "admin@example.test", "a-long-password-12345"));
        verifyNoInteractions(encoder);
    }

    @Test void rejectsNonArgon2idHashWithoutDatabaseWrite() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(false);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenReturn("bcrypt-hash");
        InitialAdminProvisioner provisioner = new InitialAdminProvisioner(preflight(false), encoder);
        assertThrows(IllegalStateException.class, () -> provisioner.provision(connection,
                "First", "Last", "admin@example.test", "a-long-password-12345"));
        verify(connection, never()).prepareStatement(anyString());
    }

    @Test void invokesRestrictedFunctionOnlyAfterVerifiedScreeningAndHashing() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(false);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        UUID expected = UUID.randomUUID();
        when(result.getObject(1)).thenReturn(expected);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenReturn("$argon2id$encoded");
        InitialAdminProvisioner provisioner = new InitialAdminProvisioner(preflight(false), encoder);
        assertEquals(expected, provisioner.provision(connection,
                "First", "Last", "admin@example.test", "a-long-password-12345"));
        verify(statement).setString(4, "$argon2id$encoded");
        verify(statement).executeQuery();
    }
}
