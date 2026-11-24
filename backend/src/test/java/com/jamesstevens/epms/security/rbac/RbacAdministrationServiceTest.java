package com.jamesstevens.epms.security.rbac;

import com.jamesstevens.epms.user.entity.User;
import com.jamesstevens.epms.user.repository.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RbacAdministrationServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final UserRepository users = mock(UserRepository.class);
    private final RbacAdministrationService service = new RbacAdministrationService(jdbc, users);

    @Test void anonymousCallerCannotAssign() {
        assertThrows(AccessDeniedException.class,
                () -> service.assign(null, UUID.randomUUID(), "PROJECT_MANAGER", "approved"));
        verifyNoInteractions(jdbc, users);
    }

    @Test void authenticatedIdentityIsResolvedFromRepository() {
        User actor = mock(User.class);
        UUID actorId = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID result = UUID.randomUUID();
        when(actor.isActive()).thenReturn(true);
        when(actor.getUserId()).thenReturn(actorId);
        when(users.findByEmailIgnoreCase("admin@example.test")).thenReturn(Optional.of(actor));
        when(jdbc.queryForObject(anyString(), eq(UUID.class), any(Object[].class))).thenReturn(result);
        var auth = new UsernamePasswordAuthenticationToken("admin@example.test", "", java.util.List.of());
        assertEquals(result, service.assign(auth, target, "PROJECT_MANAGER", " approved "));
        verify(jdbc).queryForObject(contains("epms_rbac_assign_role"), eq(UUID.class),
                eq(actorId), eq(target), eq("PROJECT_MANAGER"), eq("approved"));
    }

    @Test void blankReasonRejectedWithoutDatabaseWrite() {
        User actor = mock(User.class);
        when(actor.isActive()).thenReturn(true);
        when(users.findByEmailIgnoreCase("admin@example.test")).thenReturn(Optional.of(actor));
        var auth = new UsernamePasswordAuthenticationToken("admin@example.test", "", java.util.List.of());
        assertThrows(IllegalArgumentException.class,
                () -> service.revoke(auth, UUID.randomUUID(), " "));
        verifyNoInteractions(jdbc);
    }
}
