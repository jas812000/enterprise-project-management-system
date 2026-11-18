package com.jamesstevens.epms.user.repository;

import com.jamesstevens.epms.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for User persistence.
 *
 * <p>EPMS supports one organization per installation.
 * User records belong to that organization, and repository
 * queries preserve explicit organization ownership.</p>
 *
 * <p>The service layer is responsible for validating
 * business rules, including account-type eligibility,
 * department and supervisor relationships, and
 * user lifecycle restrictions.</p>
 *
 * <p>PostgreSQL provides final enforcement of foreign-key
 * and uniqueness constraints.</p>
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Finds a user by identifier and organization.
     *
     * @param userId user identifier
     * @param organizationId owning organization identifier
     * @return matching user, if present
     */
    Optional<User> findByUserIdAndOrganizationId(
            UUID userId,
            UUID organizationId
    );

    /**
     * Retrieves all users belonging to an organization.
     *
     * <p>Results are ordered by last name, first name,
     * and user identifier to provide deterministic ordering.</p>
     *
     * @param organizationId owning organization identifier
     * @return users belonging to the organization
     */
    List<User> findByOrganizationIdOrderByLastNameAscFirstNameAscUserIdAsc(
            UUID organizationId
    );

    /**
     * Checks whether an email address already exists
     * within an organization, ignoring letter case.
     *
     * <p>The database independently enforces uniqueness
     * through ux_users_org_email_ci.</p>
     *
     * @param organizationId owning organization identifier
     * @param email email address to check
     * @return true when a matching email exists
     */
    boolean existsByOrganizationIdAndEmailIgnoreCase(
            UUID organizationId,
            String email
    );

    /**
     * Checks whether a user exists within an organization.
     *
     * <p>Supports validation of supervisor references
     * without loading the complete user record.</p>
     *
     * @param userId referenced user identifier
     * @param organizationId owning organization identifier
     * @return true when the referenced user exists
     */
    boolean existsByUserIdAndOrganizationId(
            UUID userId,
            UUID organizationId
    );
}
