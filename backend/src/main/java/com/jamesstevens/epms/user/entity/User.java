
package com.jamesstevens.epms.user.entity;

import com.jamesstevens.epms.common.persistence.PostgresUuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * JPA persistence entity representing a user within the
 * single organization supported by an EPMS installation.
 *
 * <p>Maps to the users table defined in Flyway V1.
 * Organization ownership and relationships are enforced
 * by PostgreSQL foreign-key constraints and validated
 * by the service layer.</p>
 *
 * <p>User lifecycle operations, authentication, authorization,
 * and audit attribution are managed separately from ordinary
 * profile updates.</p>
 *
 * <p>This entity is an internal persistence model.
 * API consumers receive UserResponse DTOs instead.</p>
 */
@Entity
@Table(name = "users")
@SuppressWarnings("PMD.ShortClassName")
public class User {

    /** PostgreSQL-generated UUIDv7 primary key. */
    @Id
    @PostgresUuidV7
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Organization owning this user. Immutable after creation. */
    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    /** Optional department membership. */
    @Column(name = "department_id")
    private UUID departmentId;

    /** Optional supervisor within the organization. */
    @Column(name = "supervisor_user_id")
    private UUID supervisorUserId;

    /** Required account classification. */
    @Column(name = "account_type_id", nullable = false)
    private UUID accountTypeId;

    /** Optional service-account ownership references. */
    @Column(name = "owner_user_id")
    private UUID ownerUserId;

    @Column(name = "backup_owner_user_id")
    private UUID backupOwnerUserId;

    /** Audit attribution references. */
    @Column(name = "created_by_user_id", updatable = false)
    private UUID createdByUserId;

    @Column(name = "updated_by_user_id")
    private UUID updatedByUserId;

    @Column(name = "archived_by_user_id")
    private UUID archivedByUserId;

    @Column(name = "reviewed_by_user_id")
    private UUID reviewedByUserId;

    /** Optional organization-specific employee identifier. */
    @Column(name = "employee_number", length = 50)
    private String employeeNumber;

    /** Required user identity information. */
    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(name = "email", nullable = false, length = 320)
    private String email;

    /** Reserved for the future verified email-change workflow. */
    @Column(name = "pending_email", length = 320)
    private String pendingEmail;

    /** User lifecycle state. */
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "is_archived", nullable = false)
    private boolean archived = false;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    @Column(name = "archive_reason", columnDefinition = "TEXT")
    private String archiveReason;

    /** Usage and periodic review information. */
    @Column(name = "last_used_at")
    private OffsetDateTime lastUsedAt;

    @Column(name = "last_reviewed_at")
    private OffsetDateTime lastReviewedAt;

    @Column(name = "next_review_due_at")
    private OffsetDateTime nextReviewDueAt;

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
     * Required by JPA for entity construction.
     */
    protected User() {
        // Required by JPA.
    }

    /**
     * Creates a user with the required identity and profile data.
     *
     * <p>The service layer must validate organization ownership,
     * account type, department, supervisor, and email uniqueness
     * before persistence.</p>
     *
     * @param organizationId owning organization
     * @param departmentId optional department
     * @param supervisorUserId optional supervisor
     * @param accountTypeId required account classification
     * @param employeeNumber optional employee identifier
     * @param firstName required first name
     * @param lastName required last name
     * @param email required email address
     */
    public User(
            UUID organizationId,
            UUID departmentId,
            UUID supervisorUserId,
            UUID accountTypeId,
            String employeeNumber,
            String firstName,
            String lastName,
            String email) {

        this.organizationId = organizationId;
        this.departmentId = departmentId;
        this.supervisorUserId = supervisorUserId;
        this.accountTypeId = accountTypeId;
        this.employeeNumber = employeeNumber;
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
    }

    /**
     * Initializes timestamps before insertion.
     */
    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
    }

    /**
     * Refreshes the modification timestamp before an update.
     */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Updates ordinary profile information.
     *
     * <p>Email, organization ownership, lifecycle status,
     * audit attribution, and persistence metadata are not
     * modified through this operation.</p>
     *
     * @param departmentId optional department
     * @param supervisorUserId optional supervisor
     * @param accountTypeId validated account classification
     * @param employeeNumber optional employee identifier
     * @param firstName validated first name
     * @param lastName validated last name
     */
    public void updateDetails(
            UUID departmentId,
            UUID supervisorUserId,
            UUID accountTypeId,
            String employeeNumber,
            String firstName,
            String lastName) {

        this.departmentId = departmentId;
        this.supervisorUserId = supervisorUserId;
        this.accountTypeId = accountTypeId;
        this.employeeNumber = employeeNumber;
        this.firstName = firstName;
        this.lastName = lastName;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getDepartmentId() {
        return departmentId;
    }

    public UUID getSupervisorUserId() {
        return supervisorUserId;
    }

    public UUID getAccountTypeId() {
        return accountTypeId;
    }

    public UUID getOwnerUserId() {
        return ownerUserId;
    }

    public UUID getBackupOwnerUserId() {
        return backupOwnerUserId;
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

    public UUID getReviewedByUserId() {
        return reviewedByUserId;
    }

    public String getEmployeeNumber() {
        return employeeNumber;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getEmail() {
        return email;
    }

    public String getPendingEmail() {
        return pendingEmail;
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

    public OffsetDateTime getLastUsedAt() {
        return lastUsedAt;
    }

    public OffsetDateTime getLastReviewedAt() {
        return lastReviewedAt;
    }

    public OffsetDateTime getNextReviewDueAt() {
        return nextReviewDueAt;
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
