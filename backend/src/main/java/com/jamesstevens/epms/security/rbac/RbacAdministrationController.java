package com.jamesstevens.epms.security.rbac;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Session authentication and CSRF are enforced by the existing security filter chain. */
@RestController
@RequestMapping("/api/security/role-assignments")
@ConditionalOnProperty(name = "epms.rbac.enabled", havingValue = "true")
public class RbacAdministrationController {
    private final RbacAdministrationService service;

    public RbacAdministrationController(RbacAdministrationService service) {
        this.service = service;
    }

    public record AssignRequest(UUID targetUserId, String roleCode, String reason) { }
    public record RevokeRequest(String reason) { }
    public record AssignmentResponse(UUID assignmentId) { }

    @PostMapping
    public ResponseEntity<AssignmentResponse> assign(@RequestBody AssignRequest request,
                                                      Authentication authentication) {
        return ResponseEntity.ok(new AssignmentResponse(service.assign(authentication,
                request.targetUserId(), request.roleCode(), request.reason())));
    }

    @PostMapping("/{assignmentId}/revoke")
    public ResponseEntity<AssignmentResponse> revoke(@PathVariable UUID assignmentId,
                                                      @RequestBody RevokeRequest request,
                                                      Authentication authentication) {
        return ResponseEntity.ok(new AssignmentResponse(service.revoke(authentication,
                assignmentId, request.reason())));
    }
}
