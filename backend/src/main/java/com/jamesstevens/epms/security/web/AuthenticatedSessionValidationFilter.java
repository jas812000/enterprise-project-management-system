package com.jamesstevens.epms.security.web;

import java.util.Optional;
import com.jamesstevens.epms.user.entity.User;
import com.jamesstevens.epms.user.entity.UserCredential;
import com.jamesstevens.epms.security.service.AuthenticatedSessionValidationService;
import com.jamesstevens.epms.user.repository.UserRepository;
import com.jamesstevens.epms.user.repository.UserCredentialRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.util.Objects;

@Component
public class AuthenticatedSessionValidationFilter
        extends OncePerRequestFilter {

    private final AuthenticatedSessionValidationService validationService;
    private final UserRepository userRepository;
    private final UserCredentialRepository credentialRepository;

    public AuthenticatedSessionValidationFilter(
            AuthenticatedSessionValidationService validationService,
            UserRepository userRepository,
            UserCredentialRepository credentialRepository) {
        super();
        this.validationService = Objects.requireNonNull(validationService);
        this.userRepository = Objects.requireNonNull(userRepository);
        this.credentialRepository = Objects.requireNonNull(credentialRepository);
    }

    private static boolean isProvisionalAccessDenied(UserCredential credential) {
        return !com.jamesstevens.epms.security.service.ProvisionalCredentialAccess
                .isAuthenticationAllowed(credential, java.time.Instant.now());
    }

    @Override
    @SuppressWarnings("PMD.ConfusingTernary") // Explicit negative checks deny invalid sessions.
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();
        boolean continueChain = authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken;
        boolean validateSession = !continueChain;
        if (validateSession) {
            boolean ineligible = !validationService.isEligible(authentication.getName());
            if (ineligible) {
                SecurityContextHolder.clearContext();
                jakarta.servlet.http.HttpSession session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/problem+json");
                response.getWriter().write(
                        "{\"type\":\"about:blank\","
                                + "\"title\":\"Authentication Required\","
                                + "\"status\":401,"
                                + "\"detail\":\"Authentication is required.\"}"
                );
            } else {
                Optional<User> user = userRepository.findByEmailIgnoreCase(authentication.getName());
                Optional<UserCredential> credential = user.flatMap(
                        account -> credentialRepository.findByUserId(account.getUserId()));
                jakarta.servlet.http.HttpSession session = request.getSession(false);
                if (credential.isEmpty() || session == null
                        || !(session.getAttribute("EPMS_PASSWORD_CHANGED_AT") instanceof Long issuedAt)
                        || issuedAt != credential.get().getPasswordChangedAt().toInstant().toEpochMilli()) {
                    SecurityContextHolder.clearContext();
                    if (session != null) {
                        session.invalidate();
                    }
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                } else if (!SessionDeadlineEnforcement.isWithinDeadlines(session,
                        java.time.Instant.now())) {
                    SecurityContextHolder.clearContext();
                    session.invalidate();
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                } else if (isProvisionalAccessDenied(credential.get())) {
                    SecurityContextHolder.clearContext();
                    session.invalidate();
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                } else if (credential.get().isMustChangePassword()) {
                    String path = request.getServletPath();
                    boolean allowed = ("/api/auth/me".equals(path) && "GET".equals(request.getMethod()))
                            || ("/api/auth/csrf".equals(path) && "GET".equals(request.getMethod()))
                            || ("/api/auth/change-password".equals(path) && "POST".equals(request.getMethod()))
                            || ("/api/auth/logout".equals(path) && "POST".equals(request.getMethod()));
                    if (allowed) {
                        SessionDeadlineEnforcement.recordActivity(session, java.time.Instant.now());
                        continueChain = true;
                    } else {
                        response.sendError(HttpServletResponse.SC_FORBIDDEN);
                    }
                } else {
                    SessionDeadlineEnforcement.recordActivity(session, java.time.Instant.now());
                    continueChain = true;
                }
            }
        }
        if (continueChain) {
            filterChain.doFilter(request, response);
        }
    }
}
