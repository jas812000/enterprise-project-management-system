package com.jamesstevens.epms.team.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Observe confidential endpoint rejection status even when MVC is never reached. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnProperty(name = "epms.rbac.enabled", havingValue = "true")
public class TeamConfidentialRejectionFilter extends OncePerRequestFilter {
    private static final Logger LOGGER = LoggerFactory.getLogger(TeamConfidentialRejectionFilter.class);
    private static final Pattern CONFIDENTIAL = Pattern.compile("/api/teams/[^/]+/history/[^/]+/confidential");
    private static final Pattern ACCESS_HISTORY = Pattern.compile("/api/teams/[^/]+/confidential-access-history");

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean confidential = "POST".equals(request.getMethod()) && CONFIDENTIAL.matcher(path).matches();
        boolean history = "GET".equals(request.getMethod()) && ACCESS_HISTORY.matcher(path).matches();

        try {
            chain.doFilter(request, response);
        } finally {
            int status = response.getStatus();

            if (LOGGER.isWarnEnabled() && (confidential || history)
                    && (status == HttpServletResponse.SC_UNAUTHORIZED
                    || status == HttpServletResponse.SC_FORBIDDEN)) {
                // Only fixed route category and numeric status; no path, body, token, actor or headers.
                LOGGER.warn("TEAM_CONFIDENTIAL_HTTP_REJECTED route={} status={}",
                        confidential ? "EVENT" : "ACCESS_HISTORY", status);
            }
        }
    }
}
