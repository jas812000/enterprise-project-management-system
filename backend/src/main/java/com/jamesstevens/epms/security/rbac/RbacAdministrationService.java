package com.jamesstevens.epms.security.rbac;

import com.jamesstevens.epms.user.entity.User;
import com.jamesstevens.epms.user.repository.UserRepository;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/** Calls the database's transactional authority checks; never trusts client-supplied actor IDs. */
@Service
@ConditionalOnProperty(name = "epms.rbac.enabled", havingValue = "true")
public class RbacAdministrationService {
    private static final int MAX_REASON_LENGTH = 500;

    private final JdbcTemplate jdbc;
    private final UserRepository users;

    public RbacAdministrationService(@Qualifier("rbacJdbcTemplate") JdbcTemplate jdbc,
                                     UserRepository users) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.users = Objects.requireNonNull(users);
    }

    public UUID assign(Authentication authentication, UUID target, String role, String reason) {
        UUID actor = actorId(authentication);
        validate(reason);
        if (target == null || role == null || role.isBlank()) {
            throw new IllegalArgumentException("Target and role are required");
        }
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT public.epms_rbac_assign_role(?, ?, ?, ?)", UUID.class,
                actor, target, role, reason.trim()));
    }

    public UUID revoke(Authentication authentication, UUID assignment, String reason) {
        UUID actor = actorId(authentication);
        validate(reason);
        if (assignment == null) {
            throw new IllegalArgumentException("Assignment is required");
        }
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT public.epms_rbac_revoke_role(?, ?, ?)", UUID.class,
                actor, assignment, reason.trim()));
    }

    private UUID actorId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof org.springframework.security.authentication.AnonymousAuthenticationToken) {
            throw new AccessDeniedException("Authenticated EPMS user required");
        }
        User user = users.findByEmailIgnoreCase(authentication.getName())
                .orElseThrow(() -> new AccessDeniedException("Authenticated user unavailable"));
        if (!user.isActive() || user.isArchived()) {
            throw new AccessDeniedException("Inactive actor");
        }
        return user.getUserId();
    }

    private static void validate(String reason) {
        if (reason == null || reason.isBlank() || reason.trim().length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("Reason must be 1 to 500 characters");
        }
    }
}
