package com.jamesstevens.epms.team.web;

import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import com.jamesstevens.epms.team.service.TeamOperationsService;

/** Explicit command endpoints; no direct persistence or caller-supplied actor. */
@RestController
@RequestMapping("/api/teams/{teamId}")
@ConditionalOnProperty(name = "epms.rbac.enabled", havingValue = "true")
public class TeamOperationsController {
    private final TeamOperationsService operations;

    public TeamOperationsController(TeamOperationsService operations) {
        this.operations = operations;
    }

    public record JoinRequest(@NotNull UUID userId, @NotBlank @Size(max = 500) String reason)
            implements TeamStrictRequest { }
    public record LeaveRequest(@NotBlank String endType, @NotBlank String reasonCode,
                               @NotBlank @Size(max = 500) String comment) implements TeamStrictRequest { }
    public record AppointmentRequest(@NotNull UUID membershipId, @NotBlank @Size(max = 500) String reason)
            implements TeamStrictRequest { }
    public record ReplacementRequest(UUID replacementMembershipId, @NotBlank @Size(max = 500) String reason)
            implements TeamStrictRequest { }
    public record AlternateRequest(@NotNull UUID membershipId, @NotNull @Min(1) @Max(2) Short priority,
                                   @NotBlank @Size(max = 500) String reason) implements TeamStrictRequest { }
    public record ReasonRequest(@NotBlank @Size(max = 500) String reason) implements TeamStrictRequest { }
    public record OperationRequest(@NotBlank String type, @NotBlank @Size(max = 500) String reason)
            implements TeamStrictRequest { }
    public record CommandResponse(UUID resultId) { }

    @PostMapping("/memberships")
    public CommandResponse join(@PathVariable UUID teamId, @Valid @RequestBody JoinRequest request) {
        return new CommandResponse(operations.joinMember(teamId, request.userId(), request.reason()));
    }

    @PostMapping("/memberships/{membershipId}/end")
    public ResponseEntity<Void> leave(@PathVariable UUID teamId, @PathVariable UUID membershipId,
                                      @Valid @RequestBody LeaveRequest request) {
        operations.leaveMember(teamId, membershipId, request.endType(), request.reasonCode(), request.comment());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/leadership")
    public CommandResponse appoint(@PathVariable UUID teamId, @Valid @RequestBody AppointmentRequest request) {
        return new CommandResponse(operations.assignPermanent(teamId, request.membershipId(), request.reason()));
    }

    @PostMapping("/leadership/{assignmentId}/end")
    public ResponseEntity<Void> endLeadership(@PathVariable UUID teamId, @PathVariable UUID assignmentId,
                                              @Valid @RequestBody ReplacementRequest request) {
        operations.endPlanned(teamId, assignmentId, request.replacementMembershipId(), request.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/alternates")
    public CommandResponse designate(@PathVariable UUID teamId, @Valid @RequestBody AlternateRequest request) {
        return new CommandResponse(operations.designateAlternate(teamId, request.membershipId(),
                request.priority(), request.reason()));
    }

    @PostMapping("/alternates/{alternateId}/revoke")
    public ResponseEntity<Void> revoke(@PathVariable UUID teamId, @PathVariable UUID alternateId,
                                       @Valid @RequestBody ReasonRequest request) {
        operations.revokeAlternate(teamId, alternateId, request.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/lifecycle")
    public CommandResponse transition(@PathVariable UUID teamId, @Valid @RequestBody OperationRequest request) {
        return new CommandResponse(operations.transition(teamId, request.type(), request.reason()));
    }

    @PostMapping("/leadership/{assignmentId}/emergency")
    public CommandResponse emergency(@PathVariable UUID teamId, @PathVariable UUID assignmentId,
                                     @Valid @RequestBody OperationRequest request) {
        return new CommandResponse(operations.emergencyLeadership(teamId, assignmentId,
                request.type(), request.reason()));
    }
}
