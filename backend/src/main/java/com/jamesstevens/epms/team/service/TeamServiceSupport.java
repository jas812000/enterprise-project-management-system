package com.jamesstevens.epms.team.service;

import com.jamesstevens.epms.common.exception.ResourceConflictException;
import com.jamesstevens.epms.user.entity.User;
import com.jamesstevens.epms.user.repository.UserRepository;
import java.sql.SQLException;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Shared trusted actor resolution and sanitized database failure translation. */
final class TeamServiceSupport {
    private static final int MAX_REASON_LENGTH = 500;

    private TeamServiceSupport() {
    }

    /* default */ static void requireId(UUID identifier) {
        if (identifier == null) {
            throw new IllegalArgumentException("Identifier required");
        }
    }

    /* default */ static void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.trim().length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("Reason must be 1 to " + MAX_REASON_LENGTH + " characters");
        }
    }

    /* default */ static void requireCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Operation code required");
        }
    }

    /* default */ static void executeVoid(Runnable action) {
        execute(() -> {
            action.run();
            return null;
        });
    }

    /* default */ static UUID actorId(UserRepository users) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new AccessDeniedException("Authenticated EPMS user required");
        }

        User user = users.findByEmailIgnoreCase(authentication.getName())
                .orElseThrow(() -> new AccessDeniedException("Authenticated user unavailable"));

        if (!user.isActive() || user.isArchived()) {
            throw new AccessDeniedException("Inactive actor");
        }

        return user.getUserId();
    }

    /* default */ static <T> T execute(Supplier<T> action) {
        try {
            return action.get();
        } catch (DataAccessException exception) {
            throw translate(exception);
        }
    }

    private static RuntimeException translate(DataAccessException exception) {
        RuntimeException translated =
                new DataAccessResourceFailureException("Team operation unavailable");

        Throwable cause = exception;

        while (cause != null) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                translated = switch (sql.getSQLState()) {
                    case "42501" ->
                            new AccessDeniedException("Team operation denied");
                    case "22023" ->
                            new IllegalArgumentException("Invalid Team operation");
                    case "23505", "23514", "40001" ->
                            new ResourceConflictException("Team operation conflicts with current data");
                    default ->
                            new DataAccessResourceFailureException("Team operation unavailable");
                };

                break;
            }

            cause = cause.getCause();
        }

        return translated;
    }
}
