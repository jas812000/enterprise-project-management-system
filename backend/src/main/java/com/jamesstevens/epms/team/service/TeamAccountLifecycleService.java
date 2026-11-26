package com.jamesstevens.epms.team.service;

import com.jamesstevens.epms.team.repository.TeamCommandRepository;
import com.jamesstevens.epms.user.repository.UserRepository;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Protected human account lifecycle operations, separate from profile updates. */
@Service
@ConditionalOnProperty(name = "epms.rbac.enabled", havingValue = "true")
public final class TeamAccountLifecycleService {
    private static final Set<String> ACCOUNT_OPERATIONS = Set.of(
            "PLANNED_DEACTIVATION", "PLANNED_ARCHIVAL", "ACCOUNT_SECURITY",
            "EMERGENCY_DEACTIVATION", "EMERGENCY_TERMINATION");
    private final TeamCommandRepository commands;
    private final UserRepository users;

    public TeamAccountLifecycleService(TeamCommandRepository commands, UserRepository users) {
        this.commands = Objects.requireNonNull(commands);
        this.users = Objects.requireNonNull(users);
    }

    private static void requireAllowed(String code, Set<String> allowed) {
        if (code == null || !allowed.contains(code)) {
            throw new IllegalArgumentException("Invalid operation code");
        }
    }

    public UUID disableAccount(UUID target, String type, String reason) {
        UUID actor = TeamServiceSupport.actorId(users);
        TeamServiceSupport.requireId(target);
        requireAllowed(type, ACCOUNT_OPERATIONS);
        TeamServiceSupport.requireReason(reason);
        if (actor.equals(target)) {
            throw new IllegalArgumentException("Self-deactivation prohibited");
        }
        return TeamServiceSupport.execute(() ->
                commands.disableAccount(actor, target, type, reason));
    }

}
