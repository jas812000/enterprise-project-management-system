package com.jamesstevens.epms;

import org.junit.jupiter.api.Test;
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
			new PostgreSQLContainer("postgres:18");

	/**
	 * Verifies that the application context initializes successfully.
	 */
	@Test
	void contextLoads() {
	}
}
