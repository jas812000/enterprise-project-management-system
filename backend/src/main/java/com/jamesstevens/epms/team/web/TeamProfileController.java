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
import java.util.List;
import jakarta.validation.constraints.PositiveOrZero;
import com.jamesstevens.epms.team.dto.TeamProfile;
import com.jamesstevens.epms.team.dto.TeamProfileChange;
import com.jamesstevens.epms.team.service.TeamProfileService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** HTTP profile boundary; current authorization is performed by the protected service. */
@RestController
@RequestMapping("/api/teams")
@ConditionalOnProperty(name = "epms.rbac.enabled", havingValue = "true")
public class TeamProfileController {
    private final TeamProfileService profiles;

    public TeamProfileController(TeamProfileService profiles) {
        this.profiles = profiles;
    }

    public record ProfileRequest(@NotBlank @Size(max = 200) String name, String description,
                                 boolean override, @NotBlank @Size(max = 500) String reason,
                                 String reviewedConflicts) implements TeamStrictRequest {
        public TeamProfileChange toChange() {
            return new TeamProfileChange(name, description, override, reason, reviewedConflicts);
        }
    }

    public record UpdateRequest(@NotNull @PositiveOrZero Long version, @NotNull @Valid ProfileRequest profile)
            implements TeamStrictRequest { }
    public record ReviewRequest(UUID teamId, @NotBlank @Size(max = 200) String name) implements TeamStrictRequest { }
    public record TeamResponse(UUID teamId) { }
    public record VersionResponse(long version) { }
    public record ReviewResponse(String reviewedConflicts) { }

    @GetMapping
    public List<TeamProfile> list(@RequestParam(defaultValue = "50") int limit,
                                  @RequestParam(defaultValue = "0") int offset) {
        return profiles.list(limit, offset);
    }

    @GetMapping("/{teamId}")
    public TeamProfile get(@PathVariable UUID teamId) {
        return profiles.get(teamId);
    }

    @PostMapping
    public ResponseEntity<TeamResponse> create(@Valid @RequestBody ProfileRequest request) {
        UUID teamId = profiles.create(request.toChange());
        return ResponseEntity.created(java.net.URI.create("/api/teams/" + teamId)).body(new TeamResponse(teamId));
    }

    @PutMapping("/{teamId}")
    public VersionResponse update(@PathVariable UUID teamId, @Valid @RequestBody UpdateRequest request) {
        return new VersionResponse(profiles.update(teamId, request.version(), request.profile().toChange()));
    }

    @PostMapping("/name-conflict-review")
    public ReviewResponse review(@Valid @RequestBody ReviewRequest request) {
        return new ReviewResponse(profiles.reviewConflicts(request.teamId(), request.name()));
    }
}
