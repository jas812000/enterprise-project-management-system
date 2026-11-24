package com.jamesstevens.epms.security.bootstrap;

import com.jamesstevens.epms.security.service.PasswordScreeningDecisionService;
import com.jamesstevens.epms.security.service.PwnedPasswordScreeningService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BootstrapPasswordPreflightTest {
    private static final String PASSWORD = "long-unique-password-for-bootstrap";

    private BootstrapPasswordPreflight preflight(PwnedPasswordScreeningService.Result result) {
        PwnedPasswordScreeningService service = mock(PwnedPasswordScreeningService.class);
        when(service.screen(PASSWORD)).thenReturn(result);
        return new BootstrapPasswordPreflight(new PasswordScreeningDecisionService(service));
    }

    @Test void acceptsVerifiedPassword() {
        assertEquals(PASSWORD, preflight(PwnedPasswordScreeningService.Result.SAFE).requireVerified(PASSWORD));
    }

    @Test void rejectsCompromisedPassword() {
        assertThrows(IllegalArgumentException.class, () ->
                preflight(PwnedPasswordScreeningService.Result.COMPROMISED).requireVerified(PASSWORD));
    }

    @Test void rejectsInvalidLength() {
        assertThrows(IllegalArgumentException.class, () ->
                preflight(PwnedPasswordScreeningService.Result.SAFE).requireVerified("short"));
    }

    @Test void failsClosedWhenScreeningUnavailable() {
        PwnedPasswordScreeningService service = mock(PwnedPasswordScreeningService.class);
        when(service.screen(PASSWORD)).thenThrow(new PwnedPasswordScreeningService.ScreeningUnavailableException(new IllegalStateException("unavailable")));
        BootstrapPasswordPreflight check = new BootstrapPasswordPreflight(new PasswordScreeningDecisionService(service));
        assertThrows(IllegalArgumentException.class, () -> check.requireVerified(PASSWORD));
    }
}
