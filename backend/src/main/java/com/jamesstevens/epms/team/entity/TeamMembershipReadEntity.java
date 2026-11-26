package com.jamesstevens.epms.team.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Internal read model; not an authorization decision or a REST response. */
@Entity
@Immutable
@Table(name = "team_persistence_memberships", schema = "public")
public class TeamMembershipReadEntity {
    @Id
    @Column(name = "team_membership_id", nullable = false, insertable = false, updatable = false)
    private UUID teamMembershipId;

    @Column(name = "organization_id", nullable = false, insertable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "team_id", nullable = false, insertable = false, updatable = false)
    private UUID teamId;

    @Column(name = "user_id", nullable = false, insertable = false, updatable = false)
    private UUID userId;

    @Column(name = "membership_period_number", nullable = false, insertable = false, updatable = false)
    private int membershipPeriodNumber;

    @Column(name = "joined_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime joinedAt;

    @Column(name = "version", nullable = false, insertable = false, updatable = false)
    private long version;

    protected TeamMembershipReadEntity() {
        // Required by JPA.
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

    public int getMembershipPeriodNumber() {
        return membershipPeriodNumber;
    }

    public OffsetDateTime getJoinedAt() {
        return joinedAt;
    }

    public long getVersion() {
        return version;
    }
}
