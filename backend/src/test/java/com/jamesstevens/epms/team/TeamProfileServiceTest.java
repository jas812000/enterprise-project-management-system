package com.jamesstevens.epms.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jamesstevens.epms.common.exception.ResourceConflictException;
import com.jamesstevens.epms.team.dto.TeamProfileChange;
import com.jamesstevens.epms.team.repository.TeamCommandRepository;
import com.jamesstevens.epms.team.service.TeamProfileService;
import com.jamesstevens.epms.user.entity.User;
import com.jamesstevens.epms.user.repository.UserRepository;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class TeamProfileServiceTest {
    private final TeamCommandRepository commands = mock(TeamCommandRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final TeamProfileService service = new TeamProfileService(commands, users);
    private final UUID actor = UUID.randomUUID();
    private final TeamProfileChange change = new TeamProfileChange("Engineering", null, false, "Create");

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void unauthenticatedActorCannotReachCommands() {
        assertThrows(AccessDeniedException.class, () -> service.create(change));
        verifyNoInteractions(commands, users);
    }

    @Test
    void inactiveActorCannotReachCommands() {
        authenticate(false);
        assertThrows(AccessDeniedException.class, () -> service.list(10, 0));
        verifyNoInteractions(commands);
    }

    @Test
    void usesAuthenticatedActorAndValidatedPaging() {
        authenticate(true);
        UUID team = UUID.randomUUID();
        when(commands.create(actor, change.name(), null, false, change.reason())).thenReturn(team);
        assertEquals(team, service.create(change));
        when(commands.profiles(actor, null, 10, 20)).thenReturn(List.of());
        assertEquals(List.of(), service.list(10, 20));
        verify(commands).profiles(actor, null, 10, 20);
        assertThrows(IllegalArgumentException.class, () -> service.list(101, 0));
    }

    @Test
    void translatesDatabaseRejectionWithoutLeakingSql() {
        authenticate(true);
        when(commands.create(actor, change.name(), null, false, change.reason()))
                .thenThrow(new DataIntegrityViolationException("internal",
                        new SQLException("internal", "23514")));
        ResourceConflictException error = assertThrows(ResourceConflictException.class,
                () -> service.create(change));
        assertEquals("Team operation conflicts with current data", error.getMessage());
    }

    @Test
    void overrideRequiresExplicitReviewAndForwardsItUnchanged() {
        authenticate(true);
        TeamProfileChange missing = new TeamProfileChange("Engineering", null, true, "Create");
        assertThrows(IllegalArgumentException.class, () -> service.create(missing));
        verifyNoInteractions(commands);
        String snapshot = "reviewed snapshot";
        when(commands.reviewConflicts(actor, null, change.name())).thenReturn(snapshot);
        assertEquals(snapshot, service.reviewConflicts(null, change.name()));
        UUID team = UUID.randomUUID();
        when(commands.createReviewed(actor, change.name(), null, change.reason(), snapshot)).thenReturn(team);
        assertEquals(team, service.create(new TeamProfileChange(
                change.name(), null, true, change.reason(), snapshot)));
    }

    private void authenticate(boolean active) {
        String email = "actor@example.invalid";
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(email, null, List.of()));
        User user = mock(User.class);
        when(user.isActive()).thenReturn(active);
        when(user.getUserId()).thenReturn(actor);
        when(users.findByEmailIgnoreCase(email)).thenReturn(Optional.of(user));
    }
}
