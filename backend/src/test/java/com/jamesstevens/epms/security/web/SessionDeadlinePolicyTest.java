package com.jamesstevens.epms.security.web;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static com.jamesstevens.epms.security.web.SessionDeadlinePolicy.*;

class SessionDeadlinePolicyTest {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    @Test void validJustBeforeAuthenticationDeadline() {
        assertEquals(Result.VALID, evaluate(START.plusSeconds(899), START, START, START));
    }

    @Test void authenticationExpiresAtExactlyFifteenMinutes() {
        assertEquals(Result.REAUTHENTICATION_REQUIRED,
                evaluate(START.plusSeconds(900), START, START, START));
    }

    @Test void idleExpiresAtExactlyThirtyMinutesAfterFreshReauthentication() {
        assertEquals(Result.IDLE_EXPIRED,
                evaluate(START.plusSeconds(1800), START, START.plusSeconds(1200), START));
    }

    @Test void absoluteExpiresAtSevenDaysDespiteRecentAuthenticationAndActivity() {
        Instant now = START.plus(ABSOLUTE_LIMIT);
        assertEquals(Result.ABSOLUTE_EXPIRED,
                evaluate(now, START, now.minusSeconds(60), now.minusSeconds(60)));
    }

    @Test void rejectsFutureAndOutOfOrderTimestamps() {
        assertThrows(IllegalArgumentException.class,
                () -> evaluate(START, START.plusSeconds(1), START, START));
        assertThrows(IllegalArgumentException.class,
                () -> evaluate(START, START, START.minusSeconds(1), START));
        assertThrows(IllegalArgumentException.class,
                () -> evaluate(START, START, START, START.minusSeconds(1)));
    }


    @Test void authenticationDeadlineIsInclusiveAtNanosecondPrecision() {
        Instant deadline = START.plus(AUTHENTICATION_VALIDITY);
        assertEquals(Result.VALID, evaluate(deadline.minusNanos(1), START, START, START));
        assertEquals(Result.REAUTHENTICATION_REQUIRED, evaluate(deadline, START, START, START));
    }

    @Test void idleDeadlineIsInclusiveAtNanosecondPrecision() {
        Instant activity = START.plusSeconds(1200);
        Instant authenticated = START.plusSeconds(2400);
        Instant deadline = activity.plus(IDLE_LIMIT);
        assertEquals(Result.VALID, evaluate(deadline.minusNanos(1), START, authenticated, activity));
        assertEquals(Result.IDLE_EXPIRED, evaluate(deadline, START, authenticated, activity));
    }

    @Test void absoluteDeadlineIsInclusiveAtNanosecondPrecision() {
        Instant deadline = START.plus(ABSOLUTE_LIMIT);
        Instant recent = deadline.minusSeconds(1);
        assertEquals(Result.VALID, evaluate(deadline.minusNanos(1), START, recent, recent));
        assertEquals(Result.ABSOLUTE_EXPIRED, evaluate(deadline, START, recent, recent));
    }

    @Test void simultaneousDeadlinesPreferAbsoluteThenIdleThenAuthentication() {
        Instant now = START.plus(ABSOLUTE_LIMIT);
        assertEquals(Result.ABSOLUTE_EXPIRED, evaluate(now, START, START, START));
        Instant idleNow = START.plus(IDLE_LIMIT);
        assertEquals(Result.IDLE_EXPIRED, evaluate(idleNow, START,
                START.plusSeconds(1200), START));
    }

    @Test void rejectsNullInputs() {
        assertThrows(NullPointerException.class,
                () -> evaluate(null, START, START, START));
    }
}
