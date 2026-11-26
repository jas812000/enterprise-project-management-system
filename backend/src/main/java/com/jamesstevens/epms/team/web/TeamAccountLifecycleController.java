package com.jamesstevens.epms.team.web;

import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.jamesstevens.epms.team.service.TeamAccountLifecycleService;

/** Protected account lifecycle, separate from ordinary user profile changes. */
@RestController
@RequestMapping("/api/security/users/{userId}/lifecycle")
@ConditionalOnProperty(name = "epms.rbac.enabled", havingValue = "true")
public class TeamAccountLifecycleController {
    private final TeamAccountLifecycleService lifecycle;

    public TeamAccountLifecycleController(TeamAccountLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    public record LifecycleRequest(@NotBlank String type, @NotBlank @Size(max = 500) String reason)
            implements TeamStrictRequest { }
    public record LifecycleResponse(UUID resultId) { }

    @PostMapping
    public ResponseEntity<LifecycleResponse> disable(@PathVariable UUID userId,
                                                     @Valid @RequestBody LifecycleRequest request) {
        return ResponseEntity.ok(new LifecycleResponse(lifecycle.disableAccount(userId,
                request.type(), request.reason())));
    }
}
