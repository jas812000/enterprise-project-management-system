package com.jamesstevens.epms.team.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Opt-in system processing; no SecurityContext impersonation and no HTTP entry point. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(TeamNotificationAutomationProperties.class)
@ConditionalOnProperty(name = {"epms.rbac.enabled", "epms.team.notifications.automatic.enabled"},
        havingValue = "true")
public class TeamNotificationAutomation {
    private static final Logger LOGGER = LoggerFactory.getLogger(TeamNotificationAutomation.class);
    private final JdbcOperations jdbc;
    private final TeamNotificationAutomationProperties settings;

    public TeamNotificationAutomation(@Qualifier("rbacJdbcTemplate") JdbcOperations jdbc,
                                      TeamNotificationAutomationProperties settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    @Scheduled(fixedDelayString = "${epms.team.notifications.automatic.fixed-delay-ms:30000}",
            initialDelayString = "${epms.team.notifications.automatic.initial-delay-ms:30000}")
    public void processPending() {
        try {
            for (int index = 0; index < settings.batchSize(); index++) {
                Integer processed = jdbc.queryForObject("SELECT public.epms_team_system_dispatch(?, ?, ?)",
                        Integer.class, settings.maxAttempts(), settings.baseRetrySeconds(), settings.maxRetrySeconds());
                if (processed == null || processed == 0) {
                    break;
                }
            }
        } catch (DataAccessException exception) {
            // No raw database exception or private event details in scheduler logs.
            LOGGER.warn("Team notification processing unavailable; pending work retained");
        }
    }

    @Scheduled(fixedDelayString = "${epms.team.notifications.automatic.fixed-delay-ms:30000}",
            initialDelayString = "${epms.team.notifications.automatic.initial-delay-ms:30000}")
    public void escalatePending() {
        try {
            jdbc.queryForObject("SELECT public.epms_team_system_escalate(?, ?, ?)", Integer.class,
                    settings.batchSize(), settings.acknowledgementSeconds(), settings.repeatEscalationSeconds());
        } catch (DataAccessException exception) {
            LOGGER.warn("Team alert escalation unavailable; unresolved alerts retained");
        }
    }
}
