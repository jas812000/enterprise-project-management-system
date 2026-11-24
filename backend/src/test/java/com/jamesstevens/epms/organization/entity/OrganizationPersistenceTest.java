package com.jamesstevens.epms.organization.entity;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.hibernate.exception.ConstraintViolationException;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.EntityTransaction;
import java.time.OffsetDateTime;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class OrganizationPersistenceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:18")
                    .withInitScript("test-db-init.sql");

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    @Transactional
    void persistsOrganizationWithPostgresGeneratedUuidV7() {

        Organization organization = new Organization(
                "EPMS Test Organization",
                "Organization persistence integration test"
        );

        assertNull(organization.getOrganizationId());

        entityManager.persist(organization);
        entityManager.flush();

        UUID generatedId = organization.getOrganizationId();

        assertNotNull(generatedId);
        assertEquals(7, generatedId.version());

        entityManager.clear();

        Organization persistedOrganization =
                entityManager.find(Organization.class, generatedId);

        assertNotNull(persistedOrganization);
        assertEquals(
                "EPMS Test Organization",
                persistedOrganization.getName()
        );
        assertEquals(
                "Organization persistence integration test",
                persistedOrganization.getDescription()
        );
        assertTrue(persistedOrganization.isActive());

        assertNotNull(persistedOrganization.getCreatedAt());
        assertNotNull(persistedOrganization.getUpdatedAt());
        assertNotNull(persistedOrganization.getVersion());
    }

    @Test
    @Transactional
    void preventsCreatingMoreThanOneOrganization() {

        Organization firstOrganization = new Organization(
                "First Organization",
                "Initial EPMS organization"
        );

        entityManager.persist(firstOrganization);
        entityManager.flush();

        Organization secondOrganization = new Organization(
                "Second Organization",
                "This organization must be rejected"
        );

        entityManager.persist(secondOrganization);

        assertThrows(
                ConstraintViolationException.class,
                () -> entityManager.flush()
        );
    }

    @Test
    @Transactional
    void rejectsOrganizationWithWhitespaceOnlyName() {

        Organization organization = new Organization(
                "   ",
                "Organization with an invalid name"
        );

        entityManager.persist(organization);

        assertThrows(
                ConstraintViolationException.class,
                () -> entityManager.flush()
        );
    }

    @Test
    @Transactional
    void updatesModificationTimestampWithoutChangingCreationTimestamp() {

        Organization organization = new Organization(
                "Original Organization",
                "Original description"
        );

        entityManager.persist(organization);
        entityManager.flush();

        UUID organizationId = organization.getOrganizationId();
        OffsetDateTime originalCreatedAt = organization.getCreatedAt();
        OffsetDateTime originalUpdatedAt = organization.getUpdatedAt();
        Long originalVersion = organization.getVersion();

        entityManager.clear();

        Organization managedOrganization =
                entityManager.find(Organization.class, organizationId);

        managedOrganization.updateDetails(
                "Updated Organization",
                "Updated description"
        );

        entityManager.flush();
        entityManager.clear();

        Organization updatedOrganization =
                entityManager.find(Organization.class, organizationId);

        assertNotNull(updatedOrganization);

        assertEquals(
                "Updated Organization",
                updatedOrganization.getName()
        );

        assertEquals(
                "Updated description",
                updatedOrganization.getDescription()
        );

        assertEquals(
                originalCreatedAt.toInstant(),
                updatedOrganization.getCreatedAt().toInstant()
        );

        assertTrue(
                updatedOrganization.getUpdatedAt()
                        .isAfter(originalUpdatedAt)
        );

        assertEquals(
                originalVersion + 1,
                updatedOrganization.getVersion()
        );
    }

    @Test
    void rejectsStaleOrganizationUpdate() {

        UUID organizationId;

        // Create and commit the original organization.
        try (EntityManager setupManager =
                     entityManagerFactory.createEntityManager()) {

            EntityTransaction setupTransaction =
                    setupManager.getTransaction();

            try {
                setupTransaction.begin();

                Organization organization = new Organization(
                        "Original Organization",
                        "Original description"
                );

                setupManager.persist(organization);
                setupManager.flush();

                organizationId = organization.getOrganizationId();

                setupTransaction.commit();

            } finally {
                if (setupTransaction.isActive()) {
                    setupTransaction.rollback();
                }
            }
        }

        EntityManager managerA =
                entityManagerFactory.createEntityManager();

        EntityManager managerB =
                entityManagerFactory.createEntityManager();

        try {
            managerA.getTransaction().begin();
            managerB.getTransaction().begin();

            Organization organizationA =
                    managerA.find(Organization.class, organizationId);

            Organization organizationB =
                    managerB.find(Organization.class, organizationId);

            assertEquals(organizationA.getVersion(),
                    organizationB.getVersion());

            // Transaction A commits first.
            organizationA.updateDetails(
                    "Accounting",
                    "Updated by transaction A"
            );

            managerA.getTransaction().commit();

            // Transaction B still has the original version.
            organizationB.updateDetails(
                    "Finance",
                    "Updated by transaction B"
            );

            assertThrows(
                    OptimisticLockException.class,
                    managerB::flush
            );

        } finally {
            if (managerA.getTransaction().isActive()) {
                managerA.getTransaction().rollback();
            }

            if (managerB.getTransaction().isActive()) {
                managerB.getTransaction().rollback();
            }

            managerA.close();
            managerB.close();

            EntityManager cleanupManager =
                    entityManagerFactory.createEntityManager();

            try {
                cleanupManager.getTransaction().begin();

                Organization persistedOrganization =
                        cleanupManager.find(Organization.class, organizationId);

                if (persistedOrganization != null) {
                    cleanupManager.remove(persistedOrganization);
                }

                cleanupManager.getTransaction().commit();

            } finally {
                if (cleanupManager.getTransaction().isActive()) {
                    cleanupManager.getTransaction().rollback();
                }

                cleanupManager.close();
            }

        }
    }

}
