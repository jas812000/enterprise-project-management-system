package com.jamesstevens.epms.security.outage;

import com.jamesstevens.epms.common.persistence.PostgresUuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "password_screening_outages")
public class PasswordScreeningOutage {
    private static final int MAX_FAILURE_CATEGORY_LENGTH = 80;
    private static final String RECOVERED = "RECOVERED";
    @Id
    @PostgresUuidV7
    @Column(name = "outage_id", nullable = false, updatable = false)
    private UUID outageId;
    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;
    @Column(name = "last_failure_at", nullable = false)
    private OffsetDateTime lastFailureAt;
    @Column(name = "recovered_at")
    private OffsetDateTime recoveredAt;
    @Column(name = "status", nullable = false, length = 24)
    private String status;
    @Column(name = "failure_category", nullable = false, length = 80)
    private String failureCategory;

    protected PasswordScreeningOutage() { }

    public PasswordScreeningOutage(String category, OffsetDateTime now) {
        if (category == null || category.isBlank() || category.length() > MAX_FAILURE_CATEGORY_LENGTH) {
            throw new IllegalArgumentException("Invalid screening failure category");
        }
        startedAt = Objects.requireNonNull(now, "now");
        lastFailureAt = now;
        failureCategory = category;
        status = "SUSPECTED";
    }

    public void recordFailure(OffsetDateTime now) {
        Objects.requireNonNull(now, "now");
        if (RECOVERED.equals(status) || now.isBefore(lastFailureAt)) {
            throw new IllegalStateException("Cannot update a recovered or future-dated outage");
        }
        lastFailureAt = now;
    }

    public void confirm() {
        if (RECOVERED.equals(status)) {
            throw new IllegalStateException("Outage already recovered");
        }
        status = "CONFIRMED";
    }

    public void recover(OffsetDateTime now) {
        Objects.requireNonNull(now, "now");
        if (RECOVERED.equals(status) || now.isBefore(lastFailureAt)) {
            throw new IllegalStateException("Invalid recovery transition");
        }
        status = RECOVERED;
        recoveredAt = now;
    }

    public UUID getOutageId() { return outageId; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public OffsetDateTime getLastFailureAt() { return lastFailureAt; }
    public OffsetDateTime getRecoveredAt() { return recoveredAt; }
    public String getStatus() { return status; }
    public String getFailureCategory() { return failureCategory; }
}
