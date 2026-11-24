package com.jamesstevens.epms.security.web;

import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.Objects;

/** Fail-closed server-side deadlines for authenticated sessions. */
final class SessionDeadlineEnforcement {
    private static final String START = "EPMS_SESSION_STARTED_AT";
    private static final String AUTH = "EPMS_AUTHENTICATED_AT";
    private static final String ACTIVITY = "EPMS_LAST_ACTIVITY_AT";

    private SessionDeadlineEnforcement() { }

    @SuppressWarnings({"PMD.CommentDefaultAccessModifier", "PMD.OnlyOneReturn"})
    // Package-private security helper; early returns fail closed on malformed session state.
    static boolean isWithinDeadlines(HttpSession session, Instant now) {
        Objects.requireNonNull(session);
        Objects.requireNonNull(now);
        Object started = session.getAttribute(START);
        Object authenticated = session.getAttribute(AUTH);
        Object activity = session.getAttribute(ACTIVITY);
        if (!(started instanceof Long start) || !(authenticated instanceof Long auth)
                || !(activity instanceof Long last)) {
            return false;
        }
        try {
            return SessionDeadlinePolicy.evaluate(now, Instant.ofEpochMilli(start),
                    Instant.ofEpochMilli(auth), Instant.ofEpochMilli(last))
                    == SessionDeadlinePolicy.Result.VALID;
        } catch (IllegalArgumentException | ArithmeticException exception) {
            return false;
        }
    }

    @SuppressWarnings("PMD.CommentDefaultAccessModifier") // Package-private session helper.
    static void recordActivity(HttpSession session, Instant now) {
        session.setAttribute(ACTIVITY, Objects.requireNonNull(now).toEpochMilli());
    }
}
