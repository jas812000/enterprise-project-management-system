package com.jamesstevens.epms.security.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PasswordScreeningDecisionServiceTest {
    private static final String PASSWORD = "ValidTestPassword2026!";

    @Test void verifiedWhenProviderReturnsSafe() {
        var screening = new PwnedPasswordScreeningService(prefix -> "");
        assertEquals(PasswordScreeningDecisionService.Decision.VERIFIED,
                new PasswordScreeningDecisionService(screening).decide(PASSWORD));
    }

    @Test void compromisedPasswordIsRejected() {
        var screening = new PwnedPasswordScreeningService(prefix -> {
            var digest = java.security.MessageDigest.getInstance("SHA-1")
                    .digest(PASSWORD.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var hex = java.util.HexFormat.of().withUpperCase().formatHex(digest);
            assertEquals(hex.substring(0, 5), prefix);
            return hex.substring(5) + ":7";
        });
        assertEquals(PasswordScreeningDecisionService.Decision.REJECT_COMPROMISED,
                new PasswordScreeningDecisionService(screening).decide(PASSWORD));
    }

    @Test void outageIsNeverClassifiedAsVerified() {
        var screening = new PwnedPasswordScreeningService(prefix -> {
            throw new IllegalStateException("outage");
        });
        assertEquals(PasswordScreeningDecisionService.Decision.PROVISIONAL_REQUIRED,
                new PasswordScreeningDecisionService(screening).decide(PASSWORD));
    }

    @Test void invalidLocalPasswordNeverCallsProvider() {
        var screening = new PwnedPasswordScreeningService(prefix -> {
            fail("Provider must not be called for locally invalid password");
            return "";
        });
        assertThrows(IllegalArgumentException.class,
                () -> new PasswordScreeningDecisionService(screening).decide("short"));
    }
}
