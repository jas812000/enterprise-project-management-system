package com.jamesstevens.epms.organization.service;

import com.jamesstevens.epms.common.exception.ResourceConflictException;
import com.jamesstevens.epms.common.exception.ResourceNotFoundException;
import com.jamesstevens.epms.organization.dto.CreateOrganizationRequest;
import com.jamesstevens.epms.organization.dto.OrganizationResponse;
import com.jamesstevens.epms.organization.dto.UpdateOrganizationRequest;
import com.jamesstevens.epms.organization.repository.OrganizationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class OrganizationServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:18")
                    .withInitScript("test-db-init.sql");

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private OrganizationRepository organizationRepository;

    @BeforeEach
    void cleanDatabase() {
        organizationRepository.deleteAllInBatch();
    }

    @Test
    void createsInitialOrganization() {

        CreateOrganizationRequest request =
                new CreateOrganizationRequest(
                        "EPMS Organization",
                        "Initial organization"
                );

        OrganizationResponse response =
                organizationService.createOrganization(request);

        assertNotNull(response.organizationId());
        assertEquals(7, response.organizationId().version());
        assertEquals("EPMS Organization", response.name());
        assertEquals("Initial organization", response.description());
        assertTrue(response.isActive());
        assertNotNull(response.createdAt());
        assertNotNull(response.updatedAt());
        assertEquals(0L, response.version());
    }

    @Test
    void rejectsSecondOrganization() {

        organizationService.createOrganization(
                new CreateOrganizationRequest(
                        "First Organization",
                        "Existing organization"
                )
        );

        assertThrows(
                ResourceConflictException.class,
                () -> organizationService.createOrganization(
                        new CreateOrganizationRequest(
                                "Second Organization",
                                "Duplicate organization"
                        )
                )
        );

        assertEquals(1L, organizationRepository.count());
    }

    @Test
    void retrievesExistingOrganization() {

        OrganizationResponse created =
                organizationService.createOrganization(
                        new CreateOrganizationRequest(
                                "EPMS Organization",
                                "Existing organization"
                        )
                );

        OrganizationResponse retrieved =
                organizationService.getOrganization();

        assertEquals(
                created.organizationId(),
                retrieved.organizationId()
        );

        assertEquals(created.name(), retrieved.name());
        assertEquals(created.description(), retrieved.description());
    }

    @Test
    void rejectsRetrievalBeforeSetup() {

        assertThrows(
                ResourceNotFoundException.class,
                () -> organizationService.getOrganization()
        );
    }

    @Test
    void updatesOrganizationDetails() {

        OrganizationResponse created =
                organizationService.createOrganization(
                        new CreateOrganizationRequest(
                                "Original Organization",
                                "Original description"
                        )
                );

        OrganizationResponse updated =
                organizationService.updateOrganization(
                        new UpdateOrganizationRequest(
                                "Updated Organization",
                                "Updated description"
                        )
                );

        assertEquals(
                created.organizationId(),
                updated.organizationId()
        );

        assertEquals("Updated Organization", updated.name());
        assertEquals("Updated description", updated.description());
        assertEquals(created.createdAt(), updated.createdAt());
        assertTrue(updated.isActive());
        assertEquals(created.version() + 1, updated.version());
        assertFalse(updated.updatedAt().isBefore(created.updatedAt()));
    }

    @Test
    void rejectsUpdateBeforeSetup() {

        assertThrows(
                ResourceNotFoundException.class,
                () -> organizationService.updateOrganization(
                        new UpdateOrganizationRequest(
                                "Updated Organization",
                                "Updated description"
                        )
                )
        );
    }
}
