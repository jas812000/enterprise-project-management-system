package com.jamesstevens.epms.security.config;

import com.jamesstevens.epms.user.entity.User;
import com.jamesstevens.epms.user.entity.UserCredential;
import com.jamesstevens.epms.security.service.EpmsUserDetailsService;
import com.jamesstevens.epms.user.repository.UserRepository;
import com.jamesstevens.epms.user.repository.UserCredentialRepository;
import com.jamesstevens.epms.security.web.EpmsAuthenticationFailureHandler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.SessionManagementConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;
import com.jamesstevens.epms.security.web.AuthenticatedSessionValidationFilter;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

import java.io.IOException;

/**
 * Configures HTTP security and session-based authentication for EPMS.
 *
 * <p>Authentication uses database-backed user credentials and Argon2id
 * password hashing. Authenticated sessions are maintained by
 * Spring Security.</p>
 */
@Configuration
public class SecurityConfig {
    private static final String UNUSED_CALLBACK_PARAMETER = "PMD.UnusedFormalParameter";
    private static final int SESSION_IDLE_SECONDS = 1800;

    @Bean
    public DaoAuthenticationProvider authenticationProvider(
            EpmsUserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder) {

        DaoAuthenticationProvider provider =
                new DaoAuthenticationProvider(userDetailsService);

        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(
            DaoAuthenticationProvider provider) {

        return new ProviderManager(provider);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            DaoAuthenticationProvider provider,
            EpmsAuthenticationFailureHandler failureHandler,
            AuthenticatedSessionValidationFilter sessionValidationFilter,
            com.jamesstevens.epms.security.web.AuthenticationRateLimitFilter rateLimitFilter,
            UserRepository userRepository,
            UserCredentialRepository credentialRepository,
            com.jamesstevens.epms.security.service.AuthenticationSecurityAuditService securityAudit) {

        HttpSessionCsrfTokenRepository csrfRepository =
                new HttpSessionCsrfTokenRepository();

        http
                .authenticationProvider(provider)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/auth/csrf"
                        ).permitAll()
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/auth/login"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                )
                .sessionManagement(session -> session
                        .sessionCreationPolicy(
                                SessionCreationPolicy.IF_REQUIRED
                        )
                        .sessionFixation(
                                SessionManagementConfigurer
                                        .SessionFixationConfigurer
                                        ::changeSessionId
                        )
                )
                .requestCache(cache -> cache
                        .requestCache(new NullRequestCache())
                )
                .formLogin(form -> form
                        .loginProcessingUrl("/api/auth/login")
                        .usernameParameter("username")
                        .passwordParameter("password")
                        .successHandler((request, response, authentication) -> {
                            User user = userRepository.findByEmailIgnoreCase(authentication.getName())
                                    .orElseThrow();
                            UserCredential credential = credentialRepository.findByUserId(user.getUserId())
                                    .orElseThrow();
                            // Recheck under row lock; a concurrent failure may have locked the account.
                            boolean auditAccepted;
                            try {
                                auditAccepted = securityAudit.recordSuccessfulLogin(user.getUserId(), request);
                            } catch (RuntimeException auditFailure) { // NOPMD - Fail closed on any audit failure.
                                org.springframework.security.core.context.SecurityContextHolder.clearContext();
                                jakarta.servlet.http.HttpSession session = request.getSession(false);
                                if (session != null) {
                                    session.invalidate();
                                }
                                response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                                response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                                response.getWriter().write("{\"title\":\"Authentication Unavailable\",\"status\":503,"
                                        + "\"detail\":\"Authentication is temporarily unavailable.\"}");
                                return;
                            }
                            if (!auditAccepted) {
                                org.springframework.security.core.context.SecurityContextHolder.clearContext();
                                jakarta.servlet.http.HttpSession session = request.getSession(false);
                                if (session != null) {
                                    session.invalidate();
                                }
                                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                                response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                                response.getWriter().write("{\"title\":\"Authentication Failed\",\"status\":401,"
                                        + "\"detail\":\"Invalid username or password.\"}");
                                return;
                            }
                            jakarta.servlet.http.HttpSession authenticatedSession = request.getSession(true);
                            long authenticatedAt = java.time.Instant.now().toEpochMilli();
                            authenticatedSession.setAttribute("EPMS_PASSWORD_CHANGED_AT",
                                    credential.getPasswordChangedAt().toInstant().toEpochMilli());
                            authenticatedSession.setAttribute("EPMS_SESSION_STARTED_AT", authenticatedAt);
                            authenticatedSession.setAttribute("EPMS_AUTHENTICATED_AT", authenticatedAt);
                            authenticatedSession.setAttribute("EPMS_LAST_ACTIVITY_AT", authenticatedAt);
                            authenticatedSession.setMaxInactiveInterval(SESSION_IDLE_SECONDS);
                            handleLoginSuccess(request, response, authentication);
                        })
                        .failureHandler(failureHandler)
                )
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .logoutSuccessHandler(SecurityConfig::handleLogoutSuccess)
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(
                                SecurityConfig::handleAuthenticationRequired
                        )
                        .accessDeniedHandler(
                                SecurityConfig::handleAccessDenied
                        )
                );

        http.addFilterAfter(rateLimitFilter, org.springframework.security.web.csrf.CsrfFilter.class);

        http.addFilterAfter(
                sessionValidationFilter,
                AnonymousAuthenticationFilter.class
        );

        return http.build();
    }

    /**
     * Prevents Spring Boot from registering the session validation filter
     * directly with the servlet container.
     *
     * <p>The filter is registered explicitly in the Spring Security filter
     * chain, where the authentication context is available. Disabling
     * automatic servlet registration ensures that the filter executes only
     * at its configured position within the security chain.</p>
     *
     * @param filter the authenticated session validation filter
     * @return a disabled servlet filter registration
     */
    @Bean
    public FilterRegistrationBean<AuthenticatedSessionValidationFilter>
            sessionValidationFilterRegistration(
            AuthenticatedSessionValidationFilter filter) {

        FilterRegistrationBean<AuthenticatedSessionValidationFilter>
                registration = new FilterRegistrationBean<>(filter);

        registration.setEnabled(false);

        return registration;
    }

    @Bean
    public FilterRegistrationBean<com.jamesstevens.epms.security.web.AuthenticationRateLimitFilter>
            rateLimitFilterRegistration(com.jamesstevens.epms.security.web.AuthenticationRateLimitFilter filter) {
        FilterRegistrationBean<com.jamesstevens.epms.security.web.AuthenticationRateLimitFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * Returns HTTP 200 when authentication succeeds.
     */
    // Spring Security callback signature requires these parameters.
    @SuppressWarnings(UNUSED_CALLBACK_PARAMETER)
    private static void handleLoginSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) throws IOException {

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        response.getWriter().write(
                "{\"authenticated\":true}"
        );
    }

    /**
     * Returns HTTP 204 after logout.
     */
    // Spring Security callback signature requires these parameters.
    @SuppressWarnings(UNUSED_CALLBACK_PARAMETER)
    private static void handleLogoutSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) {

        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    /**
     * Returns HTTP 401 for requests requiring authentication.
     */
    // Spring Security callback signature requires these parameters.
    @SuppressWarnings(UNUSED_CALLBACK_PARAMETER)
    private static void handleAuthenticationRequired(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception) throws IOException {

        writeProblem(
                response,
                HttpStatus.UNAUTHORIZED,
                "Authentication Required",
                "Authentication is required."
        );
    }

    /**
     * Returns HTTP 403 for authenticated requests lacking permission.
     */
    // Spring Security callback signature requires these parameters.
    @SuppressWarnings(UNUSED_CALLBACK_PARAMETER)
    private static void handleAccessDenied(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException exception) throws IOException {

        writeProblem(
                response,
                HttpStatus.FORBIDDEN,
                "Access Denied",
                "Access to this resource is denied."
        );
    }

    /**
     * Writes a standardized HTTP ProblemDetail-compatible JSON response.
     *
     * <p>Only fixed, application-controlled strings are passed to
     * this method.</p>
     */
    private static void writeProblem(
            HttpServletResponse response,
            HttpStatus status,
            String title,
            String detail) throws IOException {

        response.setStatus(status.value());
        response.setContentType(
                MediaType.APPLICATION_PROBLEM_JSON_VALUE
        );

        response.getWriter().write(
                "{\"type\":\"about:blank\","
                        + "\"title\":\"" + title + "\","
                        + "\"status\":" + status.value() + ","
                        + "\"detail\":\"" + detail + "\"}"
        );
    }
}
