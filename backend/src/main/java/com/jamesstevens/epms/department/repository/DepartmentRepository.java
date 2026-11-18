package com.jamesstevens.epms.department.repository;

import com.jamesstevens.epms.department.entity.Department;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for reading department persistence data.
 *
 * <p>During Phase 10, department access is limited to
 * validating user department assignments. Full department
 * management is outside the scope of this phase.</p>
 */
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface DepartmentRepository
        extends JpaRepository<Department, UUID> {

    /**
     * Finds an eligible department within an organization.
     *
     * <p>An eligible department must be active and not archived.</p>
     *
     * @param departmentId department identifier
     * @param organizationId owning organization identifier
     * @return matching eligible department, if present
     */
    Optional<Department>
                findByDepartmentIdAndOrganizationIdAndActiveTrueAndArchivedFalse(
                        UUID departmentId,
                        UUID organizationId
                );
}
