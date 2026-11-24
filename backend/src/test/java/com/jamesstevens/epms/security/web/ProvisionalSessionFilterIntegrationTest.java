package com.jamesstevens.epms.security.web;

import com.jamesstevens.epms.security.service.AuthenticatedSessionValidationService;
import com.jamesstevens.epms.user.entity.User;
import com.jamesstevens.epms.user.entity.UserCredential;
import com.jamesstevens.epms.user.repository.UserRepository;
import com.jamesstevens.epms.user.repository.UserCredentialRepository;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises the actual servlet filter against an authenticated HTTP request and session. */
class ProvisionalSessionFilterIntegrationTest {
    @AfterEach void clearSecurityContext() { SecurityContextHolder.clearContext(); }

    private record Fixture(AuthenticatedSessionValidationFilter filter,
                           UserCredential credential, MockHttpServletRequest request,
                           MockHttpServletResponse response, MockFilterChain chain,
                           HttpSession session) { }

    private Fixture fixture(UserCredential credential) {
        var validation = mock(AuthenticatedSessionValidationService.class);
        var users = mock(UserRepository.class);
        var credentials = mock(UserCredentialRepository.class);
        var user = mock(User.class);
        var userId = credential.getUserId();
        when(validation.isEligible("test@example.com")).thenReturn(true);
        when(users.findByEmailIgnoreCase("test@example.com")).thenReturn(Optional.of(user));
        when(user.getUserId()).thenReturn(userId);
        when(credentials.findByUserId(userId)).thenReturn(Optional.of(credential));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("test@example.com", "N/A", java.util.List.of()));
        var request = new MockHttpServletRequest("GET", "/api/auth/me");
        var session = request.getSession(true);
        // JPA initializes passwordChangedAt in @PrePersist; this fixture has no persistence lifecycle.
        org.springframework.test.util.ReflectionTestUtils.setField(
            credential, "passwordChangedAt", OffsetDateTime.now(ZoneOffset.UTC));
        session.setAttribute("EPMS_PASSWORD_CHANGED_AT", credential.getPasswordChangedAt().toInstant().toEpochMilli());
        long now = java.time.Instant.now().toEpochMilli();
        session.setAttribute("EPMS_SESSION_STARTED_AT", now);
        session.setAttribute("EPMS_AUTHENTICATED_AT", now);
        session.setAttribute("EPMS_LAST_ACTIVITY_AT", now);
        return new Fixture(new AuthenticatedSessionValidationFilter(validation, users, credentials),
            credential, request, new MockHttpServletResponse(), new MockFilterChain(), session);
    }

    @Test void expiredProvisionalCredentialInvalidatesAuthenticatedSession() throws Exception {
        var credential = new UserCredential(UUID.randomUUID(), "encoded", "ARGON2ID", false);
        credential.issueProvisionalPassword(OffsetDateTime.now(ZoneOffset.UTC).minusHours(13),
            new byte[28], "test-key", UUID.randomUUID());
        var f = fixture(credential);
        f.filter().doFilter(f.request(), f.response(), f.chain());
        assertEquals(401, f.response().getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertThrows(IllegalStateException.class, () -> f.session().getAttribute("EPMS_PASSWORD_CHANGED_AT"));
    }

    @Test void unexpiredProvisionalCredentialKeepsAuthenticatedSession() throws Exception {
        var credential = new UserCredential(UUID.randomUUID(), "encoded", "ARGON2ID", false);
        credential.issueProvisionalPassword(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5),
            new byte[28], "test-key", UUID.randomUUID());
        var f = fixture(credential);
        f.filter().doFilter(f.request(), f.response(), f.chain());
        assertEquals(200, f.response().getStatus());
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertNotNull(f.session().getAttribute("EPMS_PASSWORD_CHANGED_AT"));
    }

    @Test void verifiedCredentialKeepsAuthenticatedSession() throws Exception {
        var f = fixture(new UserCredential(UUID.randomUUID(), "encoded", "ARGON2ID", false));
        f.filter().doFilter(f.request(), f.response(), f.chain());
        assertEquals(200, f.response().getStatus());
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test void explicitlyExpiredCredentialInvalidatesAuthenticatedSession() throws Exception {
        var credential = new UserCredential(UUID.randomUUID(), "encoded", "ARGON2ID", false);
        var issued = OffsetDateTime.now(ZoneOffset.UTC).minusHours(13);
        credential.issueProvisionalPassword(issued, new byte[28], "test-key", UUID.randomUUID());
        assertTrue(credential.expireProvisionalPassword(issued.plusHours(12)));
        var f = fixture(credential);
        f.filter().doFilter(f.request(), f.response(), f.chain());
        assertEquals(401, f.response().getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
