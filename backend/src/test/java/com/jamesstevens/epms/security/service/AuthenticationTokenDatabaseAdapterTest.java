package com.jamesstevens.epms.security.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AuthenticationTokenDatabaseAdapterTest {
    @Test
    void callsProtectedFunctionWithOnlyHashedTokens() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID user = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        byte[] access = new byte[32];
        byte[] renewal = new byte[32];
        renewal[0] = 1;
        when(jdbc.queryForObject(eq("SELECT public.epms_auth_issue(?, ?, ?)"),
                eq(UUID.class), eq(user), any(byte[].class), any(byte[].class))).thenReturn(session);
        assertEquals(session, new AuthenticationTokenDatabaseAdapter(jdbc)
                .issueAuthenticated(user, access, renewal));
        verify(jdbc).queryForObject(eq("SELECT public.epms_auth_issue(?, ?, ?)"),
                eq(UUID.class), eq(user), any(byte[].class), any(byte[].class));
    }

    @Test
    void rejectsInvalidDigestsWithoutDatabaseCall() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var adapter = new AuthenticationTokenDatabaseAdapter(jdbc);
        UUID user = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
                () -> adapter.issueAuthenticated(user, new byte[31], new byte[32]));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.issueAuthenticated(user, new byte[32], new byte[32]));
        verifyNoInteractions(jdbc);
    }
    @Test
    void validatesOnlyDigestAndDefensivelyCopiesInput() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        byte[] digest = new byte[32];
        UUID user = UUID.randomUUID();
        when(jdbc.queryForObject(eq("SELECT public.epms_auth_validate_access(?)"),
                eq(UUID.class), any(byte[].class))).thenReturn(user);
        assertEquals(user, new AuthenticationTokenDatabaseAdapter(jdbc).validateAccessDigest(digest));
        verify(jdbc).queryForObject(eq("SELECT public.epms_auth_validate_access(?)"),
                eq(UUID.class), argThat((byte[] supplied) -> supplied != digest && supplied.length == 32));
    }

    @Test
    void rejectsInvalidValidationDigestsWithoutDatabaseAccess() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var adapter = new AuthenticationTokenDatabaseAdapter(jdbc);
        assertThrows(NullPointerException.class, () -> adapter.validateAccessDigest(null));
        assertThrows(IllegalArgumentException.class, () -> adapter.validateAccessDigest(new byte[31]));
        assertThrows(IllegalArgumentException.class, () -> adapter.validateAccessDigest(new byte[33]));
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsNullInjectedJdbcTemplateBeforePublication() {
        assertThrows(NullPointerException.class,
                () -> new AuthenticationTokenDatabaseAdapter(null));
    }

}
