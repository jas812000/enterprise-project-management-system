package com.jamesstevens.epms.security.web;

import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionDeadlineFilterIntegrationTest {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    private HttpSession session(long started, long authenticated, long activity) {
        HttpSession session = mock(HttpSession.class);
        when(session.getAttribute("EPMS_SESSION_STARTED_AT")).thenReturn(started);
        when(session.getAttribute("EPMS_AUTHENTICATED_AT")).thenReturn(authenticated);
        when(session.getAttribute("EPMS_LAST_ACTIVITY_AT")).thenReturn(activity);
        return session;
    }

    @Test void acceptsActiveAuthenticatedSession() {
        HttpSession session = session(START.toEpochMilli(), START.toEpochMilli(), START.toEpochMilli());
        assertTrue(SessionDeadlineEnforcement.isWithinDeadlines(session, START.plusSeconds(899)));
    }

    @Test void rejectsAuthenticationExpiryAtBoundary() {
        HttpSession session = session(START.toEpochMilli(), START.toEpochMilli(), START.toEpochMilli());
        assertFalse(SessionDeadlineEnforcement.isWithinDeadlines(session, START.plusSeconds(900)));
    }

    @Test void rejectsIdleExpiryAfterReauthentication() {
        HttpSession session = session(START.toEpochMilli(), START.plusSeconds(1200).toEpochMilli(),
                START.toEpochMilli());
        assertFalse(SessionDeadlineEnforcement.isWithinDeadlines(session, START.plusSeconds(1800)));
    }

    @Test void rejectsAbsoluteExpiryDespiteRecentActivity() {
        Instant now = START.plus(SessionDeadlinePolicy.ABSOLUTE_LIMIT);
        HttpSession session = session(START.toEpochMilli(), now.minusSeconds(20).toEpochMilli(),
                now.minusSeconds(20).toEpochMilli());
        assertFalse(SessionDeadlineEnforcement.isWithinDeadlines(session, now));
    }

    @Test void rejectsMissingAndMalformedSessionState() {
        assertFalse(SessionDeadlineEnforcement.isWithinDeadlines(mock(HttpSession.class), START));
        HttpSession session = mock(HttpSession.class);
        when(session.getAttribute("EPMS_SESSION_STARTED_AT")).thenReturn("invalid");
        assertFalse(SessionDeadlineEnforcement.isWithinDeadlines(session, START));
    }

    @Test void activityRecordingDoesNotRenewAuthenticationOrAbsoluteStart() {
        HttpSession session = mock(HttpSession.class);
        SessionDeadlineEnforcement.recordActivity(session, START);
        verify(session).setAttribute("EPMS_LAST_ACTIVITY_AT", START.toEpochMilli());
        verify(session, never()).setAttribute(eq("EPMS_AUTHENTICATED_AT"), any());
        verify(session, never()).setAttribute(eq("EPMS_SESSION_STARTED_AT"), any());
    }
}
