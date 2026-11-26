package com.jamesstevens.epms.team.service;

import com.jamesstevens.epms.team.repository.TeamCommandRepository;
import com.jamesstevens.epms.user.repository.UserRepository;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Authenticated Team operations; PostgreSQL performs current resource authorization. */
@Service
@ConditionalOnProperty(name = "epms.rbac.enabled", havingValue = "true")
public final class TeamOperationsService {
    private static final int MAX_ALTERNATE_PRIORITY = 2;
    private static final Set<String> MEMBERSHIP_END_TYPES = Set.of("VOLUNTARY", "ADMINISTRATIVE");
    private static final Set<String> LIFECYCLE_TARGETS = Set.of("ACTIVE", "INACTIVE", "ARCHIVED");
    private static final Set<String> LEADERSHIP_EMERGENCIES = Set.of(
            "EMERGENCY_LEADERSHIP_SUSPENSION", "EMERGENCY_UNAVAILABILITY");
    private final TeamCommandRepository commands;
    private final UserRepository users;

    public TeamOperationsService(TeamCommandRepository commands, UserRepository users) {
        this.commands = Objects.requireNonNull(commands);
        this.users = Objects.requireNonNull(users);
    }

    private static void requireAllowed(String code, Set<String> allowed) {
        if (code == null || !allowed.contains(code)) {
            throw new IllegalArgumentException("Invalid operation code");
        }
    }

    public UUID joinMember(UUID team, UUID user, String reason) {
        UUID actor = TeamServiceSupport.actorId(users);
        TeamServiceSupport.requireId(team);
        TeamServiceSupport.requireId(user);
        TeamServiceSupport.requireReason(reason);
        return TeamServiceSupport.execute(() ->
                commands.joinMember(actor, team, user, reason));
    }

    public void leaveMember(UUID team, UUID membership, String endType, String reasonCode, String comment) {
        UUID actor = TeamServiceSupport.actorId(users);
        TeamServiceSupport.requireId(team);
        TeamServiceSupport.requireId(membership);
        requireAllowed(endType, MEMBERSHIP_END_TYPES);
        TeamServiceSupport.requireCode(reasonCode);
        TeamServiceSupport.requireReason(comment);
        TeamServiceSupport.executeVoid(() ->
                commands.leaveMember(actor, team, membership, endType, reasonCode, comment));
    }

    public UUID assignPermanent(UUID team, UUID membership, String reason) {
        UUID actor = TeamServiceSupport.actorId(users);
        TeamServiceSupport.requireId(team);
        TeamServiceSupport.requireId(membership);
        TeamServiceSupport.requireReason(reason);
        return TeamServiceSupport.execute(() ->
                commands.assignPermanent(actor, team, membership, reason));
    }

    public void endPlanned(UUID team, UUID assignment, UUID replacement, String reason) {
        UUID actor = TeamServiceSupport.actorId(users);
        TeamServiceSupport.requireId(team);
        TeamServiceSupport.requireId(assignment);
        TeamServiceSupport.requireReason(reason);
        TeamServiceSupport.executeVoid(() ->
                commands.endPlanned(actor, team, assignment, replacement, reason));
    }

    public UUID designateAlternate(UUID team, UUID membership, short priority, String reason) {
        UUID actor = TeamServiceSupport.actorId(users);
        TeamServiceSupport.requireId(team);
        TeamServiceSupport.requireId(membership);
        TeamServiceSupport.requireReason(reason);
        if (priority < 1 || priority > MAX_ALTERNATE_PRIORITY) {
            throw new IllegalArgumentException("Invalid alternate priority");
        }
        return TeamServiceSupport.execute(() ->
                commands.designateAlternate(actor, team, membership, priority, reason));
    }

    public void revokeAlternate(UUID team, UUID alternate, String reason) {
        UUID actor = TeamServiceSupport.actorId(users);
        TeamServiceSupport.requireId(team);
        TeamServiceSupport.requireId(alternate);
        TeamServiceSupport.requireReason(reason);
        TeamServiceSupport.executeVoid(() ->
                commands.revokeAlternate(actor, team, alternate, reason));
    }

    public UUID transition(UUID team, String status, String reason) {
        UUID actor = TeamServiceSupport.actorId(users);
        TeamServiceSupport.requireId(team);
        requireAllowed(status, LIFECYCLE_TARGETS);
        TeamServiceSupport.requireReason(reason);
        return TeamServiceSupport.execute(() ->
                commands.transition(actor, team, status, reason));
    }

    public UUID emergencyLeadership(UUID team, UUID assignment, String type, String reason) {
        UUID actor = TeamServiceSupport.actorId(users);
        TeamServiceSupport.requireId(team);
        TeamServiceSupport.requireId(assignment);
        requireAllowed(type, LEADERSHIP_EMERGENCIES);
        TeamServiceSupport.requireReason(reason);
        return TeamServiceSupport.execute(() ->
                commands.emergencyLeadership(actor, team, assignment, type, reason));
    }

    public boolean hasPermission(UUID team, String permission) {
        UUID actor = TeamServiceSupport.actorId(users);
        TeamServiceSupport.requireId(team);
        TeamServiceSupport.requireCode(permission);
        return TeamServiceSupport.execute(() ->
                commands.hasPermission(actor, team, permission));
    }

}
