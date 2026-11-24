package com.jamesstevens.epms.organization.repository;

import com.jamesstevens.epms.organization.entity.Organization;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class OrganizationRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:18")
                    .withInitScript("test-db-init.sql");

    @Autowired
    private OrganizationRepository organizationRepository;

    @Test
    @Transactional
    void returnsEmptyWhenOrganizationDoesNotExist() {

        Optional<Organization> result =
                organizationRepository.findFirstBy();

        assertTrue(result.isEmpty());
    }

    @Test
    @Transactional
    void findsExistingOrganization() {

        Organization organization = new Organization(
                "EPMS Organization",
                "Repository integration test"
        );

        Organization savedOrganization =
                organizationRepository.saveAndFlush(organization);

        Optional<Organization> result =
                organizationRepository.findFirstBy();

        assertTrue(result.isPresent());

        assertEquals(
                savedOrganization.getOrganizationId(),
                result.get().getOrganizationId()
        );

        assertEquals(
                "EPMS Organization",
                result.get().getName()
        );
    }

    @Test
    @Transactional
    void findsOrganizationById() {

        Organization organization = new Organization(
                "EPMS Organization",
                "UUID lookup test"
        );

        Organization savedOrganization =
                organizationRepository.saveAndFlush(organization);

        UUID organizationId =
                savedOrganization.getOrganizationId();

        assertNotNull(organizationId);
        assertEquals(7, organizationId.version());

        Optional<Organization> result =
                organizationRepository.findById(organizationId);

        assertTrue(result.isPresent());

        assertEquals(
                organizationId,
                result.get().getOrganizationId()
        );
    }
}
