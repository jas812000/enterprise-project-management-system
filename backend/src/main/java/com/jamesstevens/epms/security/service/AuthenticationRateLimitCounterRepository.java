package com.jamesstevens.epms.security.service;

import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Atomic, database-clock-based source counters. HTTP integration is a separate batch. */
@Repository
public class AuthenticationRateLimitCounterRepository {
    private static final int EXPECTED_COUNTER_ROWS = 1;
    private static final int COLUMN_HOUR_WINDOW_START = 3;
    private static final int COLUMN_HOUR_COUNT = 4;
    private static final int COLUMN_BLOCKED_UNTIL = 5;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public AuthenticationRateLimitCounterRepository(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record Decision(boolean allowed, Instant retryAt, int minuteCount, int hourCount) { }

    public Decision consume(String sourceKey, String policyCode, int minuteLimit, int hourLimit, Duration block) {
        Objects.requireNonNull(sourceKey, "sourceKey");
        Objects.requireNonNull(policyCode, "policyCode");
        Objects.requireNonNull(block, "block");
        if (!sourceKey.matches("[0-9a-f]{64}") || !policyCode.matches("[A-Z][A-Z0-9_]{0,47}")) {
            throw new IllegalArgumentException("Invalid rate-limit source or policy key");
        }
        if (minuteLimit < 1 || hourLimit < 1 || block.isZero() || block.isNegative()) {
            throw new IllegalArgumentException("Rate limits and block duration must be positive");
        }
        return Objects.requireNonNull(transactions.execute(_ -> consumeLocked(
                sourceKey, policyCode, minuteLimit, hourLimit, block)));
    }

    private Decision consumeLocked(String sourceKey, String policyCode, int minuteLimit,
                                   int hourLimit, Duration block) {
        Instant initial = Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT clock_timestamp()",
                        java.time.OffsetDateTime.class
                ),
                "PostgreSQL clock_timestamp() returned null"
        ).toInstant();
        Instant initialMinute = initial.truncatedTo(ChronoUnit.MINUTES);
        Instant initialHour = initial.truncatedTo(ChronoUnit.HOURS);
        jdbc.update("""
                INSERT INTO authentication_rate_limit_counters
                (source_key, policy_code, minute_window_start, hour_window_start)
                VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, sourceKey, policyCode, Timestamp.from(initialMinute), Timestamp.from(initialHour));
        java.util.List<Counter> rows = jdbc.query("""
                SELECT minute_window_start, minute_count, hour_window_start, hour_count, blocked_until
                FROM authentication_rate_limit_counters
                WHERE source_key = ? AND policy_code = ? FOR UPDATE
                """, (rs, _) -> new Counter(
                rs.getTimestamp(1).toInstant(), rs.getInt(2),
                rs.getTimestamp(COLUMN_HOUR_WINDOW_START).toInstant(), rs.getInt(COLUMN_HOUR_COUNT),
                rs.getTimestamp(COLUMN_BLOCKED_UNTIL) == null
                        ? null : rs.getTimestamp(COLUMN_BLOCKED_UNTIL).toInstant()),
                sourceKey, policyCode);
        if (rows.size() != EXPECTED_COUNTER_ROWS) {
            throw new IllegalStateException("Rate-limit counter row missing");
        }
        Counter previous = rows.getFirst();
        // Obtain time after acquiring the row lock, so queued concurrent calls use fresh time.
        Instant now = Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT clock_timestamp()",
                        java.time.OffsetDateTime.class
                ),
                "PostgreSQL clock_timestamp() returned null"
        ).toInstant();
        Decision decision;
        if (previous.blockedUntil() != null && now.isBefore(previous.blockedUntil())) {
            decision = new Decision(false, previous.blockedUntil(), previous.minuteCount(), previous.hourCount());
        } else {
            Instant minuteStart = now.truncatedTo(ChronoUnit.MINUTES);
            Instant hourStart = now.truncatedTo(ChronoUnit.HOURS);
            int minute = previous.minuteStart().equals(minuteStart) ? previous.minuteCount() : 0;
            int hour = previous.hourStart().equals(hourStart) ? previous.hourCount() : 0;
            boolean denied = minute >= minuteLimit || hour >= hourLimit;
            Instant blockedUntil = denied ? now.plus(block) : null;
            if (!denied) { minute++; hour++; }
            jdbc.update("""
                    UPDATE authentication_rate_limit_counters
                    SET minute_window_start = ?, minute_count = ?, hour_window_start = ?,
                        hour_count = ?, blocked_until = ?, updated_at = clock_timestamp()
                    WHERE source_key = ? AND policy_code = ?
                    """, Timestamp.from(minuteStart), minute, Timestamp.from(hourStart), hour,
                    blockedUntil == null ? null : Timestamp.from(blockedUntil), sourceKey, policyCode);
            decision = new Decision(!denied, blockedUntil, minute, hour);
        }
        return decision;
    }

    private record Counter(Instant minuteStart, int minuteCount, Instant hourStart,
                           int hourCount, Instant blockedUntil) { }
}
