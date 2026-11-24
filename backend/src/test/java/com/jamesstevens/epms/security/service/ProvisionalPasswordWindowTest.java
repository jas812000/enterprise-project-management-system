package com.jamesstevens.epms.security.service;

import com.jamesstevens.epms.user.entity.UserCredential;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProvisionalPasswordWindowTest {
    @Test
    void expiresExactlyTwelveHoursAfterIssuance() {
        OffsetDateTime issued = OffsetDateTime.of(2026, 11, 20, 12, 0, 0, 0, ZoneOffset.UTC);
        assertEquals(issued.plusHours(12), UserCredential.provisionalExpiration(issued));
        assertThrows(NullPointerException.class,
                () -> UserCredential.provisionalExpiration(null));
    }
}
