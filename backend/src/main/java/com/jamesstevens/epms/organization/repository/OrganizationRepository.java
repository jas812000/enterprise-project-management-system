package com.jamesstevens.epms.organization.repository;

import com.jamesstevens.epms.organization.entity.Organization;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for organization persistence.
 *
 * <p>EPMS supports at most one organization per application
 * instance. The database enforces this restriction through
 * the ux_organizations_single_instance unique index.
 *
 * <p>Provides standard JPA persistence operations and a
 * method for retrieving the organization's record without
 * requiring its identifier.
 */
public interface OrganizationRepository
        extends JpaRepository<Organization, UUID> {

    /**
     * Retrieves the organization associated with this
     * EPMS installation.
     *
     * @return the organization, or an empty Optional if
     *         initial organization setup has not occurred
     */
    Optional<Organization> findFirstBy();
}
