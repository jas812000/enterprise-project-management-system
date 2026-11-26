package com.jamesstevens.epms.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jamesstevens.epms.common.exception.ResourceConflictException;
import com.jamesstevens.epms.team.repository.TeamCommandRepository;
import com.jamesstevens.epms.team.service.TeamOperationsService;
import com.jamesstevens.epms.team.service.TeamAccountLifecycleService;
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

class TeamOperationsServiceTest {
    private final TeamCommandRepository commands = mock(TeamCommandRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final TeamOperationsService service = new TeamOperationsService(commands, users);
    private final TeamAccountLifecycleService accounts = new TeamAccountLifecycleService(commands, users);
    private final UUID actor = UUID.randomUUID();
    private final UUID team = UUID.randomUUID();
    private final UUID subject = UUID.randomUUID();
    private final UUID event = UUID.randomUUID();
    private static final String REASON = "Approved operation";

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void unauthenticatedActorCannotReachCommands() {
        assertThrows(AccessDeniedException.class, () -> service.joinMember(team, subject, REASON));
        assertThrows(AccessDeniedException.class, () -> accounts.disableAccount(subject, "ACCOUNT_SECURITY", REASON));
        verifyNoInteractions(commands, users);
    }

    @Test
    void inactiveActorCannotReachCommands() {
        authenticate(false);
        assertThrows(AccessDeniedException.class, () -> service.transition(team, "ACTIVE", REASON));
        verifyNoInteractions(commands);
    }

    @Test
    void invalidOperationsCannotReachCommands() {
        authenticate(true);
        assertThrows(IllegalArgumentException.class, () -> service.joinMember(team, subject, " "));
        assertThrows(IllegalArgumentException.class,
                () -> service.leaveMember(team, subject, "VOLUNTARY", "OTHER", ""));
        assertThrows(IllegalArgumentException.class,
                () -> service.emergencyLeadership(team, subject, "ACCOUNT_SECURITY", REASON));
        assertThrows(IllegalArgumentException.class,
                () -> accounts.disableAccount(actor, "ACCOUNT_SECURITY", REASON));
        assertThrows(IllegalArgumentException.class,
                () -> service.designateAlternate(team, subject, (short) 0, REASON));
        verifyNoInteractions(commands);
    }

    @Test
    void translatesDatabaseRejectionWithoutLeakingSql() {
        authenticate(true);
        when(commands.joinMember(actor, team, subject, REASON))
                .thenThrow(new DataIntegrityViolationException("internal",
                        new SQLException("internal", "23514")));
        ResourceConflictException error = assertThrows(ResourceConflictException.class,
                () -> service.joinMember(team, subject, REASON));
        assertEquals("Team operation conflicts with current data", error.getMessage());
    }

    @Test
    void routesJoinMemberWithTrustedActor() {
        authenticate(true);
        when(commands.joinMember(actor, team, subject, REASON)).thenReturn(event);
        assertEquals(event, service.joinMember(team, subject, REASON));
        verify(commands).joinMember(actor, team, subject, REASON);
    }

    @Test
    void routesLeaveMemberWithTrustedActor() {
        authenticate(true);
        service.leaveMember(team, subject, "VOLUNTARY", "OTHER", REASON);
        verify(commands).leaveMember(actor, team, subject, "VOLUNTARY", "OTHER", REASON);
    }

    @Test
    void routesAssignPermanentWithTrustedActor() {
        authenticate(true);
        when(commands.assignPermanent(actor, team, subject, REASON)).thenReturn(event);
        assertEquals(event, service.assignPermanent(team, subject, REASON));
        verify(commands).assignPermanent(actor, team, subject, REASON);
    }

    @Test
    void routesEndPlannedWithTrustedActor() {
        authenticate(true);
        service.endPlanned(team, subject, null, REASON);
        verify(commands).endPlanned(actor, team, subject, null, REASON);
    }

    @Test
    void routesDesignateAlternateWithTrustedActor() {
        authenticate(true);
        when(commands.designateAlternate(actor, team, subject, (short) 1, REASON)).thenReturn(event);
        assertEquals(event, service.designateAlternate(team, subject, (short) 1, REASON));
        verify(commands).designateAlternate(actor, team, subject, (short) 1, REASON);
    }

    @Test
    void routesRevokeAlternateWithTrustedActor() {
        authenticate(true);
        service.revokeAlternate(team, subject, REASON);
        verify(commands).revokeAlternate(actor, team, subject, REASON);
    }

    @Test
    void routesTransitionWithTrustedActor() {
        authenticate(true);
        when(commands.transition(actor, team, "ACTIVE", REASON)).thenReturn(event);
        assertEquals(event, service.transition(team, "ACTIVE", REASON));
        verify(commands).transition(actor, team, "ACTIVE", REASON);
    }

    @Test
    void routesEmergencyLeadershipWithTrustedActor() {
        authenticate(true);
        when(commands.emergencyLeadership(actor, team, subject, "EMERGENCY_UNAVAILABILITY", REASON)).thenReturn(event);
        assertEquals(event, service.emergencyLeadership(team, subject, "EMERGENCY_UNAVAILABILITY", REASON));
        verify(commands).emergencyLeadership(actor, team, subject, "EMERGENCY_UNAVAILABILITY", REASON);
    }

    @Test
    void routesHasPermissionWithTrustedActor() {
        authenticate(true);
        when(commands.hasPermission(actor, team, "TEAM_READ")).thenReturn(true);
        assertTrue(service.hasPermission(team, "TEAM_READ"));
        verify(commands).hasPermission(actor, team, "TEAM_READ");
    }

    @Test
    void routesDisableAccountWithTrustedActor() {
        authenticate(true);
        when(commands.disableAccount(actor, subject, "ACCOUNT_SECURITY", REASON)).thenReturn(event);
        assertEquals(event, accounts.disableAccount(subject, "ACCOUNT_SECURITY", REASON));
        verify(commands).disableAccount(actor, subject, "ACCOUNT_SECURITY", REASON);
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
