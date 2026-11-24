package com.jamesstevens.epms.security.rbac;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Keeps the restricted pool out of Spring Boot's primary DataSource bean selection. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "epms.rbac.enabled", havingValue = "true")
public class RbacJdbcConfig {
    private static final int MAX_RESTRICTED_POOL_SIZE = 3;

    private HikariDataSource restrictedPool;

    @Bean(name = "rbacJdbcTemplate")
    /* default */ JdbcTemplate rbacJdbcTemplate(
            @Value("${epms.rbac.jdbc-url}") String url,
            @Value("${epms.rbac.username}") String username,
            @Value("${epms.rbac.password}") String password) {
        if (!"epms_rbac_runtime".equals(username)) {
            throw new IllegalStateException("RBAC datasource must use epms_rbac_runtime");
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(MAX_RESTRICTED_POOL_SIZE);
        config.setPoolName("epms-rbac-restricted");
        restrictedPool = new HikariDataSource(config);
        return new JdbcTemplate(restrictedPool);
    }

    @PreDestroy
    /* default */ void closePool() {
        if (restrictedPool != null) {
            restrictedPool.close();
        }
    }
}
