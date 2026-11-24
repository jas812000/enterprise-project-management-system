package com.jamesstevens.epms.security.web;

import com.jamesstevens.epms.security.service.AuthenticationRateLimitService;
import com.jamesstevens.epms.security.service.AuthenticationRateLimitCounterRepository;
import com.jamesstevens.epms.security.service.AuthenticationSecurityAuditService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

/** Executes after CSRF checks, before authentication and controller processing. */
@Component
public class AuthenticationRateLimitFilter extends OncePerRequestFilter {
    private static final int TOO_MANY_REQUESTS = 429;
    private static final String LOGIN_PATH = "/api/auth/login";
    private static final String CHANGE_PASSWORD_PATH = "/api/auth/change-password";
    private static final String POST_METHOD = "POST";
    private static final int SERVICE_UNAVAILABLE = HttpServletResponse.SC_SERVICE_UNAVAILABLE;
    private final AuthenticationRateLimitService service;
    private final AuthenticationSecurityAuditService audit;

    public AuthenticationRateLimitFilter(AuthenticationRateLimitService service,
            AuthenticationSecurityAuditService audit) {
        super();
        this.service = service;
        this.audit = audit;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response, @NonNull FilterChain chain)
            throws ServletException, IOException {
        String selectedPolicy = policy(request);
        boolean continueChain = selectedPolicy == null;
        boolean applyRateLimit = !continueChain;
        if (applyRateLimit) {
            try {
                AuthenticationRateLimitCounterRepository.Decision decision = service.consume(request, selectedPolicy);
                boolean rateLimitExceeded = !decision.allowed();
                if (rateLimitExceeded) {
                    audit.recordRateLimited(selectedPolicy, service.sourceDigest(request), request);
                    long seconds = Math.max(1L,
                            Duration.between(Instant.now(), decision.retryAt()).getSeconds() + 1);
                    response.setHeader("Retry-After", Long.toString(seconds));
                    respond(response, TOO_MANY_REQUESTS, "Too Many Requests", "Request rate limit exceeded.");
                } else {
                    continueChain = true;
                }
            } catch (RuntimeException unavailable) { // NOPMD - Fail closed for any rate-limit/audit failure.
                respond(response, SERVICE_UNAVAILABLE, "Authentication Unavailable",
                        "Authentication is temporarily unavailable.");
            }
        }
        if (continueChain) {
            chain.doFilter(request, response);
        }
    }

    private static String policy(HttpServletRequest request) {
        String selectedPolicy = null;
        if (POST_METHOD.equals(request.getMethod())) {
            String path = request.getServletPath();
            if (LOGIN_PATH.equals(path)) {
                selectedPolicy = "LOGIN";
            } else if (CHANGE_PASSWORD_PATH.equals(path)) {
                selectedPolicy = "CHANGE_PASSWORD";
            } else if (path.matches("/api/auth/admin/users/[0-9a-fA-F-]{36}/unlock")) {
                selectedPolicy = "ADMIN_UNLOCK";
            }
        }
        return selectedPolicy;
    }

    private static void respond(HttpServletResponse response, int status,
            String title, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + title
                + "\",\"status\":" + status + ",\"detail\":\"" + detail + "\"}");
    }
}
