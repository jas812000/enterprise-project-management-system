package com.jamesstevens.epms.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Read-only JPA representation of an EPMS account type.
 *
 * <p>Account types are reference data established through
 * versioned Flyway migrations. They classify user accounts
 * rather than grant authorization permissions.</p>
 *
 * <p>This entity maps the existing account_types table.
 * Account-type administration is outside the scope of
 * Phase 10.</p>
 */
@Entity
@Table(name = "account_types")
public class AccountType {

    /** PostgreSQL-generated identifier for this account type. */
    @Id
    @Column(name = "account_type_id", nullable = false, updatable = false)
    private UUID accountTypeId;

    /** Stable classification code, such as HUMAN or SERVICE. */
    @Column(name = "code", nullable = false, length = 50)
    private String code;

    /** Human-readable classification name. */
    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    /** Optional explanation of the classification. */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** Whether the classification may be used for new users. */
    @Column(name = "is_active", nullable = false)
    private boolean active;

    /** Display ordering for reference-data consumers. */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /**
     * Required by JPA.
     */
    protected AccountType() {
        // Required by JPA.
    }

    public UUID getAccountTypeId() {
        return accountTypeId;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public boolean isActive() {
        return active;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}
