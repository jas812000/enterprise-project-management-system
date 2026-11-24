package com.jamesstevens.epms.security.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProvisionalCredentialLifecycleTest {
    private static final Instant ISSUED = Instant.parse("2026-11-20T00:00:00Z");

    private ProvisionalCredentialLifecycle at(Instant time) {
        return new ProvisionalCredentialLifecycle(Clock.fixed(time, ZoneOffset.UTC));
    }

    @Test void beforeDeadlineAllowsProvisional() {
        var lifecycle = at(ISSUED.plusSeconds(43199));
        assertEquals(ProvisionalCredentialLifecycle.Outcome.ALLOW_PROVISIONAL,
                lifecycle.evaluate(ProvisionalCredentialLifecycle.State.PENDING_VERIFICATION,
                        ISSUED, lifecycle.expirationFor(ISSUED)));
    }

    @Test void exactlyAtDeadlineRequiresReset() {
        var lifecycle = at(ISSUED.plusSeconds(43200));
        assertEquals(ProvisionalCredentialLifecycle.Outcome.REQUIRE_RESET,
                lifecycle.evaluate(ProvisionalCredentialLifecycle.State.PENDING_VERIFICATION,
                        ISSUED, lifecycle.expirationFor(ISSUED)));
    }

    @Test void malformedOrExtendedWindowRequiresReset() {
        var lifecycle = at(ISSUED.plusSeconds(10));
        assertEquals(ProvisionalCredentialLifecycle.Outcome.REQUIRE_RESET,
                lifecycle.evaluate(ProvisionalCredentialLifecycle.State.PENDING_VERIFICATION,
                        ISSUED, ISSUED.plusSeconds(86400)));
        assertEquals(ProvisionalCredentialLifecycle.Outcome.REQUIRE_RESET,
                lifecycle.evaluate(ProvisionalCredentialLifecycle.State.PENDING_VERIFICATION,
                        null, null));
    }

    @Test void stateSpecificOutcomes() {
        var lifecycle = at(ISSUED);
        assertEquals(ProvisionalCredentialLifecycle.Outcome.ALLOW_VERIFIED,
                lifecycle.evaluate(ProvisionalCredentialLifecycle.State.VERIFIED, null, null));
        assertEquals(ProvisionalCredentialLifecycle.Outcome.REQUIRE_PASSWORD_CHANGE,
                lifecycle.evaluate(ProvisionalCredentialLifecycle.State.CHANGE_REQUIRED, null, null));
        assertEquals(ProvisionalCredentialLifecycle.Outcome.REQUIRE_RESET,
                lifecycle.evaluate(ProvisionalCredentialLifecycle.State.EXPIRED, null, null));
    }

    @Test void rejectsStaleVerificationResult() {
        assertFalse(ProvisionalCredentialLifecycle.appliesToCurrentCredential("old", "new"));
        assertFalse(ProvisionalCredentialLifecycle.appliesToCurrentCredential(null, "new"));
        assertTrue(ProvisionalCredentialLifecycle.appliesToCurrentCredential("same", "same"));
    }

    @Test void subsequentIssuanceGetsIndependentWindow() {
        var lifecycle = at(ISSUED);
        Instant next = ISSUED.plusSeconds(43200);
        assertEquals(next.plusSeconds(43200), lifecycle.expirationFor(next));
        assertNotEquals(lifecycle.expirationFor(ISSUED), lifecycle.expirationFor(next));
    }
}
