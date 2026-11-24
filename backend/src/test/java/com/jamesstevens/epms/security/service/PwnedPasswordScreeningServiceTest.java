package com.jamesstevens.epms.security.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PwnedPasswordScreeningServiceTest {
    @Test
    void sendsOnlyFiveHexCharactersAndRecognizesCompromisedPassword() {
        var service = new PwnedPasswordScreeningService(prefix -> {
            assertEquals("5BAA6", prefix);
            return "1E4C9B93F3F0682250B6CF8331B7EE68FD8:3303003\n";
        });
        assertEquals(PwnedPasswordScreeningService.Result.COMPROMISED,
                service.screen("password"));
    }
    @Test
    void recognizesSafePassword() {
        var service = new PwnedPasswordScreeningService(prefix -> "");
        assertEquals(PwnedPasswordScreeningService.Result.SAFE,
                service.screen("not a real password"));
    }
    @Test
    void providerFailureNeverReturnsSafe() {
        var service = new PwnedPasswordScreeningService(prefix -> {
            throw new IllegalStateException("provider unavailable");
        });
        assertThrows(PwnedPasswordScreeningService.ScreeningUnavailableException.class,
                () -> service.screen("password"));
    }
    @Test
    void deferredDigestUsesOnlyPrefixAndMatchesSuffix() {
        var service = new PwnedPasswordScreeningService(prefix -> {
            assertEquals("5BAA6", prefix);
            return "1E4C9B93F3F0682250B6CF8331B7EE68FD8:3\n";
        });
        assertEquals(PwnedPasswordScreeningService.Result.COMPROMISED,
                service.screenDigest("5baa61e4c9b93f3f0682250b6cf8331b7ee68fd8"));
    }

    @Test
    void malformedDeferredDigestNeverCallsProvider() {
        var service = new PwnedPasswordScreeningService(prefix -> {
            fail("Provider must not receive invalid digest");
            return "";
        });
        assertThrows(IllegalArgumentException.class, () -> service.screenDigest("not-a-digest"));
    }

    @Test
    void deferredDigestProviderFailureCannotBeTreatedAsSafe() {
        var service = new PwnedPasswordScreeningService(prefix -> {
            throw new IllegalStateException("unavailable");
        });
        assertThrows(PwnedPasswordScreeningService.ScreeningUnavailableException.class,
                () -> service.screenDigest("5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8"));
    }

    @Test
    void malformedResponseIsNotAcceptedAsSafe() {
        var service = new PwnedPasswordScreeningService(prefix -> "bad response");
        assertThrows(PwnedPasswordScreeningService.ScreeningUnavailableException.class,
                () -> service.screen("password"));
    }
}
