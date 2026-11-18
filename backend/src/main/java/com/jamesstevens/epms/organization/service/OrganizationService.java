package com.jamesstevens.epms.organization.service;

import com.jamesstevens.epms.common.exception.ResourceConflictException;
import com.jamesstevens.epms.common.exception.ResourceNotFoundException;
import com.jamesstevens.epms.organization.dto.CreateOrganizationRequest;
import com.jamesstevens.epms.organization.dto.OrganizationResponse;
import com.jamesstevens.epms.organization.dto.UpdateOrganizationRequest;
import com.jamesstevens.epms.organization.entity.Organization;
import com.jamesstevens.epms.organization.repository.OrganizationRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provides business operations for managing the organization
 * associated with an EPMS installation.
 *
 * <p>EPMS supports exactly one organization after initial setup.
 * The database prevents multiple organization records, while
 * this service enforces the corresponding business rules.
 *
 * <p>Organization entities remain internal to the persistence
 * layer. Public operations return OrganizationResponse DTOs.
 */
@Service
public class OrganizationService {

    private final OrganizationRepository organizationRepository;

    /**
     * Creates the service with its required repository dependency.
     *
     * @param organizationRepository repository for organization persistence
     */
    public OrganizationService(
            OrganizationRepository organizationRepository) {
        this.organizationRepository = organizationRepository;
    }

    /**
     * Creates the initial organization for this EPMS installation.
     *
     * <p>Checks whether an organization already exists before inserting.
     * The database unique constraint provides final protection against
     * concurrent creation requests.
     *
     * @param request validated organization creation information
     * @return the newly created organization
     * @throws ResourceConflictException if an organization already exists
     */
    @Transactional
    public OrganizationResponse createOrganization(
            CreateOrganizationRequest request) {

        if (organizationRepository.findFirstBy().isPresent()) {
            throw new ResourceConflictException(
                    "An organization already exists for this EPMS installation."
            );
        }

        Organization organization = new Organization(
                request.name(),
                request.description()
        );

        try {
            Organization savedOrganization =
                    organizationRepository.saveAndFlush(organization);

            return toResponse(savedOrganization);

        } catch (DataIntegrityViolationException exception) {

            if (isSingleOrganizationConstraintViolation(exception)) {
                throw new ResourceConflictException(
                        "An organization already exists for this EPMS installation."
                );
            }

            throw exception;
        }
    }

    /**
     * Determines whether a persistence failure was caused by
     * the database's single-organization unique constraint.
     *
     * <p>Checks Hibernate's constraint information and the
     * PostgreSQL SQLSTATE without directly depending on
     * PostgreSQL JDBC implementation classes.
     *
     * @param exception the persistence exception
     * @return true if the single-organization constraint was violated
     */
    private boolean isSingleOrganizationConstraintViolation(
            DataIntegrityViolationException exception) {

        Throwable cause = exception;

        while (cause != null) {

            if (cause instanceof ConstraintViolationException constraintException) {

                String constraintName = constraintException.getConstraintName();
                String sqlState = constraintException.getSQLState();

                return "23505".equals(sqlState)
                        && "ux_organizations_single_instance".equals(
                        constraintName
                );
            }

            cause = cause.getCause();
        }

        return false;
    }

    /**
     * Retrieves the organization associated with this installation.
     *
     * @return the existing organization
     * @throws ResourceNotFoundException if setup has not occurred
     */
    @Transactional(readOnly = true)
    public OrganizationResponse getOrganization() {

        Organization organization = organizationRepository.findFirstBy()
                .orElseThrow(() -> new ResourceNotFoundException(
                        "The organization has not been configured."
                ));

        return toResponse(organization);
    }

    /**
     * Updates the organization's editable business information.
     *
     * @param request validated organization update information
     * @return the updated organization
     * @throws ResourceNotFoundException if setup has not occurred
     */
    @Transactional
    public OrganizationResponse updateOrganization(
            UpdateOrganizationRequest request) {

        Organization organization = organizationRepository.findFirstBy()
                .orElseThrow(() -> new ResourceNotFoundException(
                        "The organization has not been configured."
                ));

        organization.updateDetails(
                request.name(),
                request.description()
        );

        Organization updatedOrganization =
                organizationRepository.saveAndFlush(organization);

        return toResponse(updatedOrganization);
    }

    /**
     * Converts an internal Organization entity into its API response.
     *
     * @param organization organization persistence entity
     * @return organization response DTO
     */
    private OrganizationResponse toResponse(Organization organization) {

        return new OrganizationResponse(
                organization.getOrganizationId(),
                organization.getName(),
                organization.getDescription(),
                organization.isActive(),
                organization.getCreatedAt(),
                organization.getUpdatedAt(),
                organization.getVersion()
        );
    }
}
