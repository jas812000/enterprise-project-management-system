package com.jamesstevens.epms.team;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jamesstevens.epms.team.service.TeamNotificationAutomation;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcOperations;

/** A real Spring scheduler invokes both fixed commands without a human security context. */
class TeamNotificationAutomationSchedulingTest {
    private static final int WAIT_MILLIS = 5000;
    private static final String DISPATCH = "SELECT public.epms_team_system_dispatch(?, ?, ?)";
    private static final String ESCALATE = "SELECT public.epms_team_system_escalate(?, ?, ?)";

    @Test
    void schedulerIsOptInAndInvokesBothCommands() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        try (var disabled = context(jdbc, false)) {
            assertTrue(disabled.getBeansOfType(TeamNotificationAutomation.class).isEmpty());
        }
        when(jdbc.queryForObject(eq(DISPATCH), eq(Integer.class), anyInt(), anyInt(), anyInt())).thenReturn(0);
        try (var enabled = context(jdbc, true)) {
            assertTrue(enabled.getBeansOfType(TeamNotificationAutomation.class).size() == 1);
            verify(jdbc, timeout(WAIT_MILLIS).atLeastOnce())
                    .queryForObject(eq(DISPATCH), eq(Integer.class), anyInt(), anyInt(), anyInt());
            verify(jdbc, timeout(WAIT_MILLIS).atLeastOnce())
                    .queryForObject(eq(ESCALATE), eq(Integer.class), anyInt(), anyInt(), anyInt());
        }
    }

    private static AnnotationConfigApplicationContext context(JdbcOperations jdbc, boolean enabled) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("notification-test", Map.of(
                "epms.rbac.enabled", "true", "epms.team.notifications.automatic.enabled", Boolean.toString(enabled),
                "epms.team.notifications.automatic.fixed-delay-ms", "50",
                "epms.team.notifications.automatic.initial-delay-ms", "0")));
        context.registerBean("rbacJdbcTemplate", JdbcOperations.class, () -> jdbc);
        context.register(TeamNotificationAutomation.class);
        context.refresh();
        return context;
    }
}
