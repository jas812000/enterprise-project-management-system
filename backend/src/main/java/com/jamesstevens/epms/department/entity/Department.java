package com.jamesstevens.epms.department.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Read-only JPA representation of an EPMS department.
 *
 * <p>Each department belongs to the single organization
 * supported by an EPMS installation.</p>
 *
 * <p>This entity supports department eligibility checks
 * during user creation and profile updates. Full department
 * management and lifecycle operations are outside the
 * scope of Phase 10.</p>
 *
 * <p>The database schema is managed by Flyway.</p>
 */
@Entity
@Table(name = "departments")
public class Department {

    /** PostgreSQL-generated UUIDv7 primary key. */
    @Id
    @Column(name = "department_id", nullable = false, updatable = false)
    private UUID departmentId;

    /** Organization that owns this department. */
    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    /** Audit attribution references. */
    @Column(name = "created_by_user_id", updatable = false)
    private UUID createdByUserId;

    @Column(name = "updated_by_user_id")
    private UUID updatedByUserId;

    @Column(name = "archived_by_user_id")
    private UUID archivedByUserId;

    /** Department details. */
    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** Department lifecycle state. */
    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "is_archived", nullable = false)
    private boolean archived;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    @Column(name = "archive_reason", columnDefinition = "TEXT")
    private String archiveReason;

    /** Persistence timestamps. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** Hibernate-managed optimistic locking version. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /**
     * Required by JPA.
     */
    protected Department() {
        // Required by JPA.
    }

    public UUID getDepartmentId() {
        return departmentId;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getCreatedByUserId() {
        return createdByUserId;
    }

    public UUID getUpdatedByUserId() {
        return updatedByUserId;
    }

    public UUID getArchivedByUserId() {
        return archivedByUserId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isArchived() {
        return archived;
    }

    public OffsetDateTime getArchivedAt() {
        return archivedAt;
    }

    public String getArchiveReason() {
        return archiveReason;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}
