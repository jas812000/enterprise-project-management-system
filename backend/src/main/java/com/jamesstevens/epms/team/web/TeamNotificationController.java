package com.jamesstevens.epms.team.web;

import com.jamesstevens.epms.team.dto.TeamAlternateReadiness;
import com.jamesstevens.epms.team.dto.TeamDispatchFailure;
import com.jamesstevens.epms.team.dto.TeamConfidentialAccessRecord;
import com.jamesstevens.epms.team.dto.TeamHistoryEvent;
import com.jamesstevens.epms.team.dto.TeamNotification;
import com.jamesstevens.epms.team.dto.TeamSecurityAlert;
import com.jamesstevens.epms.team.service.TeamNotificationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** In-app notification and administrative alert endpoints; actor identity stays server-side. */
@RestController
@RequestMapping("/api")
@ConditionalOnProperty(name = "epms.rbac.enabled", havingValue = "true")
public class TeamNotificationController {
    private final TeamNotificationService notifications;

    public TeamNotificationController(TeamNotificationService notifications) {
        this.notifications = notifications;
    }

    public record DispatchResponse(int processedEvents) { }
    public record ResolutionRequest(@NotBlank @Size(max = 500) String reason) implements TeamStrictRequest { }

    @GetMapping("/team-notifications")
    public List<TeamNotification> inbox(@RequestParam(defaultValue = "50") int limit,
                                        @RequestParam(defaultValue = "0") int offset) {
        return notifications.inbox(limit, offset);
    }

    @PostMapping("/team-notifications/dispatch")
    public DispatchResponse dispatch(@RequestParam(defaultValue = "50") int limit) {
        return new DispatchResponse(notifications.dispatch(limit));
    }

    @GetMapping("/team-notifications/dispatch-failures")
    public List<TeamDispatchFailure> failures(@RequestParam(defaultValue = "50") int limit,
                                              @RequestParam(defaultValue = "0") int offset) {
        return notifications.failures(limit, offset);
    }

    @PostMapping("/team-notifications/{notificationId}/read")
    public ResponseEntity<Void> read(@PathVariable UUID notificationId) {
        notifications.read(notificationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/team-notifications/{notificationId}/acknowledge")
    public ResponseEntity<Void> acknowledge(@PathVariable UUID notificationId) {
        notifications.acknowledge(notificationId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/team-notifications/alerts")
    public List<TeamSecurityAlert> alerts(@RequestParam(defaultValue = "false") boolean includeResolved,
                                         @RequestParam(defaultValue = "50") int limit,
                                         @RequestParam(defaultValue = "0") int offset) {
        return notifications.alerts(includeResolved, limit, offset);
    }

    @PostMapping("/team-notifications/alerts/{alertId}/acknowledge")
    public ResponseEntity<Void> acknowledgeAlert(@PathVariable UUID alertId) {
        notifications.acknowledgeAlert(alertId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/team-notifications/alerts/{alertId}/resolve")
    public ResponseEntity<Void> resolveAlert(@PathVariable UUID alertId,
                                             @Valid @RequestBody ResolutionRequest request) {
        notifications.resolveAlert(alertId, request.reason());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/teams/{teamId}/alternate-readiness")
    public List<TeamAlternateReadiness> readiness(@PathVariable UUID teamId) {
        return notifications.readiness(teamId);
    }

    @GetMapping("/teams/{teamId}/history")
    public List<TeamHistoryEvent> history(@PathVariable UUID teamId,
                                         @RequestParam(defaultValue = "50") int limit,
                                         @RequestParam(defaultValue = "0") int offset) {
        return notifications.history(teamId, limit, offset);
    }
    public record ConfidentialAuditRequest(@NotBlank String sourceKind,
                                           @NotBlank @Size(max = 500) String justification)
            implements TeamStrictRequest { }

    @PostMapping("/teams/{teamId}/history/{eventId}/confidential")
    public ResponseEntity<String> confidentialEvent(@PathVariable UUID teamId, @PathVariable UUID eventId,
                                                     @Valid @RequestBody ConfidentialAuditRequest request) {
        String detail = notifications.confidentialEvent(teamId, eventId,
                request.sourceKind(), request.justification());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON).body(detail);
    }

    @GetMapping("/teams/{teamId}/confidential-access-history")
    public ResponseEntity<List<TeamConfidentialAccessRecord>> accessHistory(@PathVariable UUID teamId,
            @RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(notifications.accessHistory(teamId, limit, offset));
    }
}
