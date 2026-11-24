package com.jamesstevens.epms.security.web;

import com.jamesstevens.epms.security.service.AuthenticationRateLimitCounterRepository;
import com.jamesstevens.epms.security.service.AuthenticationRateLimitService;
import com.jamesstevens.epms.security.service.AuthenticationSecurityAuditService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthenticationRateLimitFilterTest {
    private final AuthenticationRateLimitService service = mock(AuthenticationRateLimitService.class);
    private final AuthenticationSecurityAuditService audit = mock(AuthenticationSecurityAuditService.class);
    private final AuthenticationRateLimitFilter filter = new AuthenticationRateLimitFilter(service, audit);

    @Test void loginIsRateLimitedBeforeAuthentication() throws Exception {
        var request = request("POST", "/api/auth/login");
        var response = new MockHttpServletResponse();
        when(service.consume(request, "LOGIN")).thenReturn(
                new AuthenticationRateLimitCounterRepository.Decision(false, Instant.now().plusSeconds(300), 20, 20));
        when(service.sourceDigest(request)).thenReturn("a".repeat(64));
        AtomicInteger downstream = new AtomicInteger();
        FilterChain chain = (req, res) -> downstream.incrementAndGet();
        filter.doFilter(request, response, chain);
        assertEquals(429, response.getStatus());
        assertTrue(Integer.parseInt(response.getHeader("Retry-After")) > 0);
        assertEquals(0, downstream.get());
        verify(audit).recordRateLimited("LOGIN", "a".repeat(64), request);
    }

    @Test void passwordChangeAndUnlockHaveSeparatePolicies() throws Exception {
        for (String[] target : new String[][] {
                {"/api/auth/change-password", "CHANGE_PASSWORD"},
                {"/api/auth/admin/users/0199a000-0000-7000-8000-000000000001/unlock", "ADMIN_UNLOCK"}}) {
            var request = request("POST", target[0]);
            when(service.consume(request, target[1])).thenReturn(
                    new AuthenticationRateLimitCounterRepository.Decision(true, null, 1, 1));
            var response = new MockHttpServletResponse();
            AtomicInteger called = new AtomicInteger();
            filter.doFilter(request, response, (req, res) -> called.incrementAndGet());
            assertEquals(1, called.get());
            verify(service).consume(request, target[1]);
        }
    }

    @Test void unrelatedMethodsBypassRepository() throws Exception {
        var request = request("GET", "/api/auth/login");
        AtomicInteger called = new AtomicInteger();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> called.incrementAndGet());
        assertEquals(1, called.get());
        verifyNoInteractions(service);
    }

    @Test void databaseFailureFailsClosed() throws Exception {
        var request = request("POST", "/api/auth/login");
        when(service.consume(request, "LOGIN")).thenThrow(new IllegalStateException("database down"));
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> fail("Must not reach authentication"));
        assertEquals(503, response.getStatus());
        assertFalse(response.getContentAsString().contains("database down"));
    }

    @Test void auditFailureFailsClosedAndDoesNotSend429() throws Exception {
        var request = request("POST", "/api/auth/login");
        when(service.consume(request, "LOGIN")).thenReturn(
                new AuthenticationRateLimitCounterRepository.Decision(false, Instant.now().plusSeconds(300), 21, 21));
        when(service.sourceDigest(request)).thenReturn("b".repeat(64));
        doThrow(new IllegalStateException("audit storage unavailable"))
                .when(audit).recordRateLimited("LOGIN", "b".repeat(64), request);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> fail("Must not reach authentication"));
        assertEquals(503, response.getStatus());
        assertNull(response.getHeader("Retry-After"));
        assertFalse(response.getContentAsString().contains("audit storage unavailable"));
    }

    @Test void allowedRequestsDoNotWriteRateLimitAudit() throws Exception {
        var request = request("POST", "/api/auth/login");
        when(service.consume(request, "LOGIN")).thenReturn(
                new AuthenticationRateLimitCounterRepository.Decision(true, null, 1, 1));
        AtomicInteger downstream = new AtomicInteger();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> downstream.incrementAndGet());
        assertEquals(1, downstream.get());
        verifyNoInteractions(audit);
    }

    private static MockHttpServletRequest request(String method, String path) {
        var request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        return request;
    }
}
