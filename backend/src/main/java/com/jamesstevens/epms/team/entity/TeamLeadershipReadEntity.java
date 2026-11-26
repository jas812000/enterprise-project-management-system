package com.jamesstevens.epms.team.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Internal read model; not an authorization decision or a REST response. */
@Entity
@Immutable
@Table(name = "team_persistence_leadership", schema = "public")
public class TeamLeadershipReadEntity {
    @Id
    @Column(name = "team_leadership_assignment_id", nullable = false, insertable = false, updatable = false)
    private UUID teamLeadershipAssignmentId;

    @Column(name = "team_membership_id", nullable = false, insertable = false, updatable = false)
    private UUID teamMembershipId;

    @Column(name = "organization_id", nullable = false, insertable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "team_id", nullable = false, insertable = false, updatable = false)
    private UUID teamId;

    @Column(name = "user_id", nullable = false, insertable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "assignment_type", nullable = false, insertable = false, updatable = false, length = 20)
    private TeamLeadershipType assignmentType;

    @Column(name = "assigned_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime assignedAt;

    @Column(name = "version", nullable = false, insertable = false, updatable = false)
    private long version;

    protected TeamLeadershipReadEntity() {
        // Required by JPA.
    }

    public UUID getTeamLeadershipAssignmentId() {
        return teamLeadershipAssignmentId;
    }

    public UUID getTeamMembershipId() {
        return teamMembershipId;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getTeamId() {
        return teamId;
    }

    public UUID getUserId() {
        return userId;
    }

    public TeamLeadershipType getAssignmentType() {
        return assignmentType;
    }

    public OffsetDateTime getAssignedAt() {
        return assignedAt;
    }

    public long getVersion() {
        return version;
    }
}
