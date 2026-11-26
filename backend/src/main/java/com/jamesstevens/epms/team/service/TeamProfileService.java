package com.jamesstevens.epms.team.service;

import com.jamesstevens.epms.common.exception.ResourceNotFoundException;
import com.jamesstevens.epms.team.dto.TeamNameConflict;
import com.jamesstevens.epms.team.dto.TeamProfile;
import com.jamesstevens.epms.team.dto.TeamProfileChange;
import com.jamesstevens.epms.team.repository.TeamCommandRepository;
import com.jamesstevens.epms.user.repository.UserRepository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Scoped profile reads and administrative mutations with current database authorization. */
@Service
@ConditionalOnProperty(name = "epms.rbac.enabled", havingValue = "true")
public final class TeamProfileService {
    private final TeamCommandRepository commands;
    private final UserRepository users;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_REASON_LENGTH = 500;
    private static final int MAX_TEAM_NAME_LENGTH = 200;

    public TeamProfileService(TeamCommandRepository commands, UserRepository users) {
        this.commands = Objects.requireNonNull(commands);
        this.users = Objects.requireNonNull(users);
    }

    public UUID create(TeamProfileChange change) {
        UUID actor = TeamServiceSupport.actorId(users);
        validate(change);
        return TeamServiceSupport.execute(() -> change.override()
                ? commands.createReviewed(actor, change.name(), change.description(),
                        change.reason(), change.reviewedConflicts())
                : commands.create(actor, change.name(), change.description(), false, change.reason()));
    }

    public long update(UUID team, long version, TeamProfileChange change) {
        UUID actor = TeamServiceSupport.actorId(users);
        validate(change);
        if (team == null || version < 0) {
            throw new IllegalArgumentException("Team and nonnegative version required");
        }
        return TeamServiceSupport.execute(() -> change.override()
                ? commands.updateProfileReviewed(actor, team, version, change.name(),
                        change.description(), change.reason(), change.reviewedConflicts())
                : commands.updateProfile(actor, team, version, change.name(),
                        change.description(), false, change.reason()));
    }

    public TeamProfile get(UUID team) {
        UUID actor = TeamServiceSupport.actorId(users);
        if (team == null) {
            throw new IllegalArgumentException("Team required");
        }
        return TeamServiceSupport.execute(() -> commands.profiles(actor, team, 1, 0)).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Team unavailable"));
    }

    public List<TeamProfile> list(int limit, int offset) {
        UUID actor = TeamServiceSupport.actorId(users);

        if (limit < 1 || limit > MAX_PAGE_SIZE || offset < 0) {
            throw new IllegalArgumentException("Invalid Team page");
        }

        return List.copyOf(
                TeamServiceSupport.execute(() -> commands.profiles(actor, null, limit, offset))
        );
    }

    public List<TeamNameConflict> conflicts(UUID team, String name) {
        UUID actor = TeamServiceSupport.actorId(users);
        validateName(name);
        return List.copyOf(
                TeamServiceSupport.execute(() -> commands.profileConflicts(actor, team, name)));
    }

    public String reviewConflicts(UUID team, String name) {
        UUID actor = TeamServiceSupport.actorId(users);
        validateName(name);
        return TeamServiceSupport.execute(() -> commands.reviewConflicts(actor, team, name));
    }

    private static void validate(TeamProfileChange change) {
        if (change == null || change.reason() == null || change.reason().isBlank()
                || change.reason().trim().length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException(
                    "Reason must be 1 to " + MAX_REASON_LENGTH + " characters"
            );
        }

        validateName(change.name());
        if (change.override() && (change.reviewedConflicts() == null || change.reviewedConflicts().isBlank())) {
            throw new IllegalArgumentException("Current name conflict review required");
        }
        if (!change.override() && change.reviewedConflicts() != null) {
            throw new IllegalArgumentException("Unexpected name conflict review");
        }
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()
                || name.trim().length() > MAX_TEAM_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "Team name must be 1 to " + MAX_TEAM_NAME_LENGTH + " characters"
            );
        }
    }

}
