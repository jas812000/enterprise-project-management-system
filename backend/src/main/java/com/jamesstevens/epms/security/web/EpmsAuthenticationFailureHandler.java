package com.jamesstevens.epms.security.web;

import com.jamesstevens.epms.organization.repository.OrganizationRepository;
import com.jamesstevens.epms.user.repository.UserCredentialRepository;
import com.jamesstevens.epms.user.repository.UserRepository;
import com.jamesstevens.epms.user.service.UserCredentialService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;

import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class EpmsAuthenticationFailureHandler
        implements AuthenticationFailureHandler {

    private final com.jamesstevens.epms.security.service.AuthenticationSecurityAuditService securityAudit;
    private final UserRepository userRepository;
    private final UserCredentialRepository credentialRepository;
    private final OrganizationRepository organizationRepository;
    private final UserCredentialService credentialService;

    public EpmsAuthenticationFailureHandler(
            UserRepository userRepository,
            UserCredentialRepository credentialRepository,
            OrganizationRepository organizationRepository,
            UserCredentialService credentialService,
            com.jamesstevens.epms.security.service.AuthenticationSecurityAuditService securityAudit) {

        this.userRepository = userRepository;
        this.credentialRepository = credentialRepository;
        this.organizationRepository = organizationRepository;
        this.credentialService = credentialService;
        this.securityAudit = java.util.Objects.requireNonNull(securityAudit, "securityAudit");
    }

    @Override
    public void onAuthenticationFailure(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull AuthenticationException exception) throws IOException {

        // Audit every rejection, including unknown, inactive and already-locked accounts.
        // Required audit failure returns a generic service-unavailable response.
        try {
            securityAudit.recordRejectedLogin(request);
        } catch (RuntimeException auditFailure) { // NOPMD - Audit failure must return 503.
            // Required audit unavailable: never silently accept an untracked attempt.
            // Do not expose exception details or account existence to the client.
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write(
                    "{\"title\":\"Authentication Unavailable\","
                    + "\"status\":503,\"detail\":"
                    + "\"Authentication is temporarily unavailable.\"}");
            return;
        }


        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(
                MediaType.APPLICATION_PROBLEM_JSON_VALUE
        );

        response.getWriter().write("""
                {
                  "type": "about:blank",
                  "title": "Authentication Failed",
                  "status": 401,
                  "detail": "Invalid username or password."
                }
                """);
    }


}
