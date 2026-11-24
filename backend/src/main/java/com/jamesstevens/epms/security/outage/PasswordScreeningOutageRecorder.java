package com.jamesstevens.epms.security.outage;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists a screening failure incident and its security event in one transaction. */
@Service
public class PasswordScreeningOutageRecorder {
    private final PasswordScreeningOutageRepository outages;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public PasswordScreeningOutageRecorder(PasswordScreeningOutageRepository outages,
                                          JdbcTemplate jdbc) {
        this(outages, jdbc, Clock.systemUTC());
    }

    /* package-private: injectable dependencies for deterministic outage tests. */
    /* default */ PasswordScreeningOutageRecorder(PasswordScreeningOutageRepository outages,
                                    JdbcTemplate jdbc, Clock clock) {
        this.outages = Objects.requireNonNull(outages);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Caller must provide a non-sensitive category, never an exception message. */
    @Transactional
    public UUID recordFailure(String category) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        PasswordScreeningOutage outage = outages.saveAndFlush(new PasswordScreeningOutage(category, now));
        UUID outageId = Objects.requireNonNull(outage.getOutageId(), "Outage identifier was not generated");
        jdbc.update("INSERT INTO password_security_events "
                + "(event_id, occurred_at, outage_id, event_type) "
                + "VALUES (uuidv7(), ?, ?, 'SCREENING_FAILURE')", now, outageId);
        return outageId;
    }
}
