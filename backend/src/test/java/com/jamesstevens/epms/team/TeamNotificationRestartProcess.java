package com.jamesstevens.epms.team;

import com.jamesstevens.epms.EpmsApplication;
import com.jamesstevens.epms.team.service.TeamNotificationAutomation;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;

/** Separate-JVM entry point for disposable full-application restart acceptance. */
public final class TeamNotificationRestartProcess {
    private TeamNotificationRestartProcess() { }

    public static void main(String[] args) throws InterruptedException {
        String url = System.getenv("SPRING_DATASOURCE_URL");
        validateDisposableDatasource(url);

        boolean recovery = System.getenv("EPMS_TEST_RESTART_SOURCE") != null;
        String delay = recovery ? "1000" : "600000";

        try (var context = new SpringApplicationBuilder(EpmsApplication.class).run(
                "--server.address=127.0.0.1", "--server.port=0", "--spring.flyway.enabled=false",
                "--spring.jpa.hibernate.ddl-auto=validate", "--epms.rbac.enabled=true",
                "--epms.team.notifications.automatic.enabled=true",
                "--epms.team.notifications.automatic.initial-delay-ms=" + delay,
                "--epms.team.notifications.automatic.fixed-delay-ms=300000",
                "--epms.security.deferred-recovery.enabled=false",
                "--epms.team.notifications.automatic.base-retry-seconds=1",
                "--epms.team.notifications.automatic.max-retry-seconds=1",
                "--epms.team.notifications.automatic.max-attempts=2")) {
            if (recovery) {
                awaitScheduledDelivery(context.getBean(JdbcTemplate.class),
                        UUID.fromString(System.getenv("EPMS_TEST_RESTART_SOURCE")));
            } else {
                context.getBean(TeamNotificationAutomation.class).processPending();
            }
        }
    }

    private static void validateDisposableDatasource(String url) {
        if (url == null || !url.startsWith("jdbc:")) {
            throw new IllegalStateException("Disposable JDBC datasource required");
        }

        URI datasource = URI.create(url.substring("jdbc:".length()));

        if (!"postgresql".equals(datasource.getScheme())) {
            throw new IllegalStateException("PostgreSQL datasource required");
        }

        String host = datasource.getHost();
        if (!"localhost".equals(host) && !"127.0.0.1".equals(host)) {
            throw new IllegalStateException("Disposable loopback datasource required");
        }

        int port = datasource.getPort();
        if (port <= 0 || port > 65_535 || port == 5432) {
            throw new IllegalStateException("Reject shared or developer database port");
        }

        String path = datasource.getPath();
        if (path == null || path.length() <= 1
                || datasource.getUserInfo() != null
                || datasource.getFragment() != null) {
            throw new IllegalStateException("Invalid disposable database target");
        }

        if (!url.equals(System.getenv("EPMS_RBAC_JDBC_URL"))) {
            throw new IllegalStateException("Datasource targets must match");
        }
    }

    private static void awaitScheduledDelivery(JdbcTemplate jdbc, UUID source)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);

        while (System.nanoTime() < deadline) {
            Boolean delivered = jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM public.team_notification_attempts
                    WHERE COALESCE(operation_event_id,lifecycle_event_id)=?
                    AND outcome='DELIVERED' AND attempt_no=2 AND processor_kind='SYSTEM')
                    """, Boolean.class, source);

            if (Boolean.TRUE.equals(delivered)) {
                return;
            }

            TimeUnit.MILLISECONDS.sleep(100);
        }

        throw new IllegalStateException("Scheduled restart did not recover pending notification");
    }
}
