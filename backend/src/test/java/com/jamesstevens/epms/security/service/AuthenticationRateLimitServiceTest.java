package com.jamesstevens.epms.security.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthenticationRateLimitServiceTest {
    private final AuthenticationRateLimitCounterRepository repository = mock(AuthenticationRateLimitCounterRepository.class);
    private final AuthenticationRateLimitService service = new AuthenticationRateLimitService(
            repository, "test-only-hmac-key-with-at-least-32-characters");

    @Test void usesDirectAddressAndApprovedPolicies() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.11");
        request.addHeader("X-Forwarded-For", "198.51.100.99");
        service.consume(request, "LOGIN");
        service.consume(request, "CHANGE_PASSWORD");
        service.consume(request, "ADMIN_UNLOCK");
        verify(repository).consume(matches("[0-9a-f]{64}"), eq("LOGIN"), eq(20), eq(100), eq(Duration.ofMinutes(5)));
        verify(repository).consume(matches("[0-9a-f]{64}"), eq("CHANGE_PASSWORD"), eq(10), eq(10), eq(Duration.ofMinutes(5)));
        verify(repository).consume(matches("[0-9a-f]{64}"), eq("ADMIN_UNLOCK"), eq(10), eq(10), eq(Duration.ofMinutes(5)));
    }

    @Test void forwardedHeadersCannotChangeSourceDigest() {
        var first = new MockHttpServletRequest();
        first.setRemoteAddr("192.0.2.11");
        first.addHeader("X-Forwarded-For", "198.51.100.1");
        var second = new MockHttpServletRequest();
        second.setRemoteAddr("192.0.2.11");
        second.addHeader("X-Forwarded-For", "203.0.113.200");
        assertEquals(service.sourceDigest(first), service.sourceDigest(second));
        assertTrue(service.sourceDigest(first).matches("[0-9a-f]{64}"));
        assertNotEquals("192.0.2.11", service.sourceDigest(first));
    }

    @Test void shortSecretAndAbsentSourceFailClosed() {
        var shortKey = new AuthenticationRateLimitService(repository, "too-short");
        assertThrows(IllegalStateException.class,
                () -> shortKey.consume(new MockHttpServletRequest(), "LOGIN"));
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("");
        assertThrows(IllegalStateException.class, () -> service.consume(request, "LOGIN"));
        verifyNoInteractions(repository);
    }

    @Test void missingSecretFailsClosed() {
        var missing = new AuthenticationRateLimitService(repository, "");
        assertThrows(IllegalStateException.class, () -> missing.consume(new MockHttpServletRequest(), "LOGIN"));
        verifyNoInteractions(repository);
    }
}
