package com.jamesstevens.epms.security.outage;

import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import static org.junit.jupiter.api.Assertions.*;

class PasswordScreeningOutageTest {
    private static final OffsetDateTime T = OffsetDateTime.of(2026, 11, 20, 12, 0, 0, 0, ZoneOffset.UTC);

    @Test void lifecycle() {
        var outage = new PasswordScreeningOutage("PROVIDER_TIMEOUT", T);
        assertEquals("SUSPECTED", outage.getStatus());
        outage.recordFailure(T.plusMinutes(1));
        outage.confirm();
        assertEquals("CONFIRMED", outage.getStatus());
        outage.recover(T.plusMinutes(2));
        assertEquals("RECOVERED", outage.getStatus());
        assertEquals(T.plusMinutes(2), outage.getRecoveredAt());
        assertThrows(IllegalStateException.class, () -> outage.recordFailure(T.plusMinutes(3)));
        assertThrows(IllegalStateException.class, () -> outage.confirm());
        assertThrows(IllegalStateException.class, () -> outage.recover(T.plusMinutes(4)));
    }

    @Test void invalidTransitions() {
        assertThrows(IllegalArgumentException.class, () -> new PasswordScreeningOutage(" ", T));
        var outage = new PasswordScreeningOutage("PROVIDER_TIMEOUT", T);
        assertThrows(IllegalStateException.class, () -> outage.recordFailure(T.minusSeconds(1)));
        assertThrows(IllegalStateException.class, () -> outage.recover(T.minusSeconds(1)));
    }
}
