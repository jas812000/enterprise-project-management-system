package com.jamesstevens.epms.team.repository;

import com.jamesstevens.epms.team.dto.TeamAlternateReadiness;
import com.jamesstevens.epms.team.dto.TeamDispatchFailure;
import com.jamesstevens.epms.team.dto.TeamConfidentialAccessRecord;
import com.jamesstevens.epms.team.dto.TeamHistoryEvent;
import com.jamesstevens.epms.team.dto.TeamNotification;
import com.jamesstevens.epms.team.dto.TeamSecurityAlert;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;

/** Restricted commands only; notifications never participate in Team mutation transactions. */
@Repository
@ConditionalOnProperty(name = "epms.rbac.enabled", havingValue = "true")
public class TeamNotificationRepository {
    private final JdbcOperations jdbc;

    public TeamNotificationRepository(@Qualifier("rbacJdbcTemplate") JdbcOperations jdbc) {
        this.jdbc = jdbc;
    }

    public int dispatch(UUID actor, int limit) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT public.epms_team_command_dispatch_notifications(?, ?)", Integer.class, actor, limit));
    }

    public List<TeamDispatchFailure> failures(UUID actor, int limit, int offset) {
        return jdbc.query("SELECT * FROM public.epms_team_command_dispatch_failures(?, ?, ?)",
                (row, index) -> new TeamDispatchFailure(row.getObject("source_id", UUID.class),
                        row.getObject("team_id", UUID.class), row.getString("source_kind"),
                        row.getInt("attempt_count"), row.getString("failure_sqlstate"),
                        row.getObject("attempted_at", OffsetDateTime.class),
                        row.getObject("next_retry_at", OffsetDateTime.class),
                        row.getBoolean("automatic_exhausted")), actor, limit, offset);
    }

    public List<TeamNotification> inbox(UUID actor, int limit, int offset) {
        return jdbc.query("SELECT * FROM public.epms_team_command_notification_inbox(?, ?, ?)",
                (row, index) -> new TeamNotification(row.getObject("notification_id", UUID.class),
                        row.getObject("team_id", UUID.class), row.getString("kind"), row.getString("severity"),
                        row.getObject("occurred_at", OffsetDateTime.class),
                        row.getObject("read_at", OffsetDateTime.class),
                        row.getObject("acknowledged_at", OffsetDateTime.class)), actor, limit, offset);
    }

    public void receipt(UUID actor, UUID notification, String action) {
        jdbc.queryForObject("SELECT public.epms_team_command_notification_receipt(?, ?, ?)",
                Object.class, actor, notification, action);
    }

    public List<TeamSecurityAlert> alerts(UUID actor, boolean includeResolved, int limit, int offset) {
        return jdbc.query("SELECT * FROM public.epms_team_command_security_alerts(?, ?, ?, ?)",
                (row, index) -> new TeamSecurityAlert(row.getObject("alert_id", UUID.class),
                        row.getObject("team_id", UUID.class), row.getString("alert_code"),
                        row.getObject("created_at", OffsetDateTime.class),
                        row.getObject("acknowledged_at", OffsetDateTime.class),
                        row.getObject("resolved_at", OffsetDateTime.class), row.getBoolean("acknowledged_by_any"),
                        row.getInt("escalation_count"), row.getObject("last_escalated_at", OffsetDateTime.class),
                        row.getString("last_escalation_stage")),
                actor, includeResolved, limit, offset);
    }

    public void acknowledgeAlert(UUID actor, UUID alert) {
        jdbc.queryForObject("SELECT public.epms_team_command_acknowledge_alert(?, ?)", Object.class, actor, alert);
    }

    public void resolveAlert(UUID actor, UUID alert, String reason) {
        jdbc.queryForObject("SELECT public.epms_team_command_resolve_alert(?, ?, ?)",
                Object.class, actor, alert, reason);
    }

    public List<TeamAlternateReadiness> readiness(UUID actor, UUID team) {
        return jdbc.query("SELECT * FROM public.epms_team_command_alternate_readiness(?, ?)",
                (row, index) -> new TeamAlternateReadiness(row.getInt("priority"), row.getString("readiness")),
                actor, team);
    }

    public List<TeamHistoryEvent> history(UUID actor, UUID team, int limit, int offset) {
        return jdbc.query("SELECT * FROM public.epms_team_command_operation_history(?, ?, ?, ?)",
                (row, index) -> new TeamHistoryEvent(row.getObject("event_id", UUID.class),
                        row.getString("source_kind"), row.getString("event_kind"),
                        row.getObject("occurred_at", OffsetDateTime.class)), actor, team, limit, offset);
    }
    public String confidentialEvent(UUID actor, UUID team, UUID event, String source, String justification) {
        return jdbc.queryForObject("SELECT public.epms_team_command_confidential_event(?, ?, ?, ?, ?)",
                String.class, actor, team, event, source, justification);
    }

    public List<TeamConfidentialAccessRecord> accessHistory(UUID actor, UUID team, int limit, int offset) {
        return jdbc.query("SELECT * FROM public.epms_team_command_confidential_access_history(?, ?, ?, ?)",
                (row, index) -> new TeamConfidentialAccessRecord(row.getObject("access_id", UUID.class),
                        row.getObject("actor_user_id", UUID.class), row.getObject("requested_team_id", UUID.class),
                        row.getObject("requested_event_id", UUID.class), row.getString("source_kind"),
                        row.getString("outcome"), row.getString("access_scope"),
                        row.getObject("occurred_at", OffsetDateTime.class)), actor, team, limit, offset);
    }
}
