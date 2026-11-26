package com.jamesstevens.epms.team.service;

import com.jamesstevens.epms.team.dto.TeamAlternateReadiness;
import com.jamesstevens.epms.team.dto.TeamDispatchFailure;
import com.jamesstevens.epms.team.dto.TeamConfidentialAccessRecord;
import com.jamesstevens.epms.team.dto.TeamHistoryEvent;
import com.jamesstevens.epms.team.dto.TeamNotification;
import com.jamesstevens.epms.team.dto.TeamSecurityAlert;
import com.jamesstevens.epms.team.repository.TeamNotificationRepository;
import com.jamesstevens.epms.user.repository.UserRepository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Authenticated notification, acknowledgement, readiness and metadata-history boundary. */
@Service
@ConditionalOnProperty(name = "epms.rbac.enabled", havingValue = "true")
public final class TeamNotificationService {
    private static final int MAX_PAGE_SIZE = 100;
    private final TeamNotificationRepository notifications;
    private final UserRepository users;

    public TeamNotificationService(TeamNotificationRepository notifications, UserRepository users) {
        this.notifications = Objects.requireNonNull(notifications);
        this.users = Objects.requireNonNull(users);
    }

    private static void requirePage(int limit, int offset) {
        if (limit < 1 || limit > MAX_PAGE_SIZE || offset < 0) {
            throw new IllegalArgumentException("Invalid notification page");
        }
    }

    public int dispatch(int limit) {
        UUID actor = TeamServiceSupport.actorId(users);
        requirePage(limit, 0);
        return TeamServiceSupport.execute(() -> notifications.dispatch(actor, limit));
    }

    public List<TeamDispatchFailure> failures(int limit, int offset) {
        UUID actor = TeamServiceSupport.actorId(users);
        requirePage(limit, offset);
        return List.copyOf(TeamServiceSupport.execute(() -> notifications.failures(actor, limit, offset)));
    }

    public List<TeamNotification> inbox(int limit, int offset) {
        UUID actor = TeamServiceSupport.actorId(users);
        requirePage(limit, offset);
        return List.copyOf(TeamServiceSupport.execute(() -> notifications.inbox(actor, limit, offset)));
    }

    public void read(UUID notification) {
        receipt(notification, "READ");
    }

    public void acknowledge(UUID notification) {
        receipt(notification, "ACKNOWLEDGE");
    }

    private void receipt(UUID notification, String action) {
        UUID actor = TeamServiceSupport.actorId(users);
        TeamServiceSupport.requireId(notification);
        TeamServiceSupport.executeVoid(() -> notifications.receipt(actor, notification, action));
    }

    public List<TeamSecurityAlert> alerts(boolean includeResolved, int limit, int offset) {
        UUID actor = TeamServiceSupport.actorId(users);
        requirePage(limit, offset);
        return List.copyOf(TeamServiceSupport.execute(() ->
                notifications.alerts(actor, includeResolved, limit, offset)));
    }

    public void acknowledgeAlert(UUID alert) {
        UUID actor = TeamServiceSupport.actorId(users);
        TeamServiceSupport.requireId(alert);
        TeamServiceSupport.executeVoid(() -> notifications.acknowledgeAlert(actor, alert));
    }

    public void resolveAlert(UUID alert, String reason) {
        UUID actor = TeamServiceSupport.actorId(users);
        TeamServiceSupport.requireId(alert);
        TeamServiceSupport.requireReason(reason);
        TeamServiceSupport.executeVoid(() -> notifications.resolveAlert(actor, alert, reason));
    }

    public List<TeamAlternateReadiness> readiness(UUID team) {
        UUID actor = TeamServiceSupport.actorId(users);
        TeamServiceSupport.requireId(team);
        return List.copyOf(TeamServiceSupport.execute(() -> notifications.readiness(actor, team)));
    }

    public List<TeamHistoryEvent> history(UUID team, int limit, int offset) {
        UUID actor = TeamServiceSupport.actorId(users);
        TeamServiceSupport.requireId(team);
        requirePage(limit, offset);
        return List.copyOf(TeamServiceSupport.execute(() -> notifications.history(actor, team, limit, offset)));
    }
    public String confidentialEvent(UUID team, UUID event, String source, String justification) {
        UUID actor = TeamServiceSupport.actorId(users);
        TeamServiceSupport.requireId(team);
        TeamServiceSupport.requireId(event);
        TeamServiceSupport.requireCode(source);
        TeamServiceSupport.requireReason(justification);
        if (!"OPERATION".equals(source) && !"LIFECYCLE".equals(source)) {
            throw new IllegalArgumentException("Invalid audit source");
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Confidential audit requires independent execution");
        }
        String detail = TeamServiceSupport.execute(() ->
                notifications.confidentialEvent(actor, team, event, source, justification));
        if (detail == null) {
            throw new AccessDeniedException("Confidential audit denied");
        }
        return detail;
    }

    public List<TeamConfidentialAccessRecord> accessHistory(UUID team, int limit, int offset) {
        UUID actor = TeamServiceSupport.actorId(users);
        TeamServiceSupport.requireId(team);
        requirePage(limit, offset);
        return List.copyOf(TeamServiceSupport.execute(() -> notifications.accessHistory(actor, team, limit, offset)));
    }
}
