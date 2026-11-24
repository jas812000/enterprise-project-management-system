package com.jamesstevens.epms;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Verifies that the EPMS application context starts successfully
 * against an isolated PostgreSQL database.
 *
 * <p>Testcontainers provisions PostgreSQL for the test, while
 * Spring Boot configures the datasource through {@link ServiceConnection}.
 * Flyway applies the database migrations before Hibernate validates
 * the entity mappings.</p>
 */
@SpringBootTest
@Testcontainers
class EpmsApplicationTests {

	/**
	 * PostgreSQL 18 instance used exclusively for this test class.
	 */
	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres =
			new PostgreSQLContainer("postgres:18")
					.withInitScript("test-db-init.sql");

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void securityAdministrationRoleIsRestricted() {
		var attributes = jdbc.queryForMap("SELECT rolcanlogin, rolsuper, rolcreaterole, rolcreatedb, rolinherit FROM pg_roles WHERE rolname = ?", "epms_security_admin");
		assertFalse((Boolean) attributes.get("rolcanlogin"));
		assertFalse((Boolean) attributes.get("rolsuper"));
		assertFalse((Boolean) attributes.get("rolcreaterole"));
		assertFalse((Boolean) attributes.get("rolcreatedb"));
		assertFalse((Boolean) attributes.get("rolinherit"));
		assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM pg_roles WHERE rolname = ?", Integer.class, "epms_security_admin"));
	}

	/**
	 * Verifies that the application context initializes successfully.
	 */
	@Test
	void contextLoads() {
	}
}
