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
@Table(name = "team_persistence_teams", schema = "public")
public class TeamReadEntity {
    @Id
    @Column(name = "team_id", nullable = false, insertable = false, updatable = false)
    private UUID teamId;

    @Column(name = "organization_id", nullable = false, insertable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "name", nullable = false, insertable = false, updatable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_status", nullable = false, insertable = false, updatable = false, length = 32)
    private TeamLifecycleStatus lifecycleStatus;

    @Column(name = "is_active", nullable = false, insertable = false, updatable = false)
    private boolean active;

    @Column(name = "is_archived", nullable = false, insertable = false, updatable = false)
    private boolean archived;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "version", nullable = false, insertable = false, updatable = false)
    private long version;

    protected TeamReadEntity() {
        // Required by JPA.
    }

    public UUID getTeamId() {
        return teamId;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getName() {
        return name;
    }

    public TeamLifecycleStatus getLifecycleStatus() {
        return lifecycleStatus;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isArchived() {
        return archived;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
