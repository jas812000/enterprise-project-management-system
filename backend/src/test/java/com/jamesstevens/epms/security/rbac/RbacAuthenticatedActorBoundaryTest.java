package com.jamesstevens.epms.security.rbac;

import com.jamesstevens.epms.user.entity.User;
import com.jamesstevens.epms.user.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Tests that HTTP callers cannot supply an actor UUID to bypass identity checks. */
class RbacAuthenticatedActorBoundaryTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final UserRepository users = mock(UserRepository.class);
    private final RbacAdministrationService service = new RbacAdministrationService(jdbc, users);

    private static UsernamePasswordAuthenticationToken authenticated() {
        return new UsernamePasswordAuthenticationToken("member@example.test", "", List.of());
    }

    @Test void unknownAuthenticatedIdentityCannotAssign() {
        when(users.findByEmailIgnoreCase("member@example.test")).thenReturn(Optional.empty());
        assertThrows(AccessDeniedException.class, () ->
                service.assign(authenticated(), UUID.randomUUID(), "ORG_ADMIN", "test"));
        verifyNoInteractions(jdbc);
    }

    @Test void inactiveAuthenticatedIdentityCannotRevoke() {
        User actor = mock(User.class);
        when(users.findByEmailIgnoreCase("member@example.test")).thenReturn(Optional.of(actor));
        when(actor.isActive()).thenReturn(false);
        assertThrows(AccessDeniedException.class, () ->
                service.revoke(authenticated(), UUID.randomUUID(), "test"));
        verifyNoInteractions(jdbc);
    }

    @Test void archivedAuthenticatedIdentityCannotAssign() {
        User actor = mock(User.class);
        when(users.findByEmailIgnoreCase("member@example.test")).thenReturn(Optional.of(actor));
        when(actor.isActive()).thenReturn(true);
        when(actor.isArchived()).thenReturn(true);
        assertThrows(AccessDeniedException.class, () ->
                service.assign(authenticated(), UUID.randomUUID(), "ORG_ADMIN", "test"));
        verifyNoInteractions(jdbc);
    }
}
