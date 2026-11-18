package com.jamesstevens.epms.user.service;

import com.jamesstevens.epms.common.exception.ResourceConflictException;
import com.jamesstevens.epms.common.exception.ResourceNotFoundException;
import com.jamesstevens.epms.department.repository.DepartmentRepository;
import com.jamesstevens.epms.organization.entity.Organization;
import com.jamesstevens.epms.organization.repository.OrganizationRepository;
import com.jamesstevens.epms.user.dto.CreateUserRequest;
import com.jamesstevens.epms.user.dto.UpdateUserRequest;
import com.jamesstevens.epms.user.dto.UserResponse;
import com.jamesstevens.epms.user.entity.AccountType;
import com.jamesstevens.epms.user.entity.User;
import com.jamesstevens.epms.user.repository.AccountTypeRepository;
import com.jamesstevens.epms.user.repository.UserRepository;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Provides persistence-backed business operations for EPMS users.
 *
 * <p>Each installation supports exactly one organization.
 * The service derives organization ownership from the configured
 * organization rather than accepting it from request DTOs.</p>
 *
 * <p>Account classifications, department assignments, and
 * supervisor relationships are validated before persistence.</p>
 *
 * <p>Authentication, authorization, email verification, and
 * user deactivation workflows are outside Phase 10.</p>
 */
@Service
public class UserService {

    private static final String EMAIL_UNIQUE_CONSTRAINT =
            "ux_users_org_email_ci";
    private static final String SYSTEM_ACCOUNT_TYPE = "SYSTEM";

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final AccountTypeRepository accountTypeRepository;
    private final DepartmentRepository departmentRepository;

    /**
     * Creates the service with its persistence dependencies.
     *
     * @param userRepository repository for user records
     * @param organizationRepository repository for the organization
     * @param accountTypeRepository account classification repository
     * @param departmentRepository department repository
     */
    public UserService(
            UserRepository userRepository,
            OrganizationRepository organizationRepository,
            AccountTypeRepository accountTypeRepository,
            DepartmentRepository departmentRepository) {

        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.accountTypeRepository = accountTypeRepository;
        this.departmentRepository = departmentRepository;
    }

    /**
     * Creates a user within the configured organization.
     *
     * @param request validated user creation information
     * @return newly created user information
     * @throws ResourceNotFoundException if required references
     *         do not exist or are not eligible
     * @throws ResourceConflictException if the email is already used
     *         or a supervisor relationship is invalid
     */
    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        UUID organizationId = getOrganizationId();

        validateAccountType(request.accountTypeId());
        validateDepartment(request.departmentId(), organizationId);
        validateSupervisor(
                request.supervisorUserId(),
                organizationId,
                null
        );

        String email = normalizeEmail(request.email());

        if (userRepository.existsByOrganizationIdAndEmailIgnoreCase(
                organizationId,
                email)) {
            throw new ResourceConflictException(
                    "A user with this email address already exists."
            );
        }

        User user = new User(
                organizationId,
                request.departmentId(),
                request.supervisorUserId(),
                request.accountTypeId(),
                request.employeeNumber(),
                request.firstName(),
                request.lastName(),
                email
        );

        try {
            return toResponse(userRepository.saveAndFlush(user));
        } catch (DataIntegrityViolationException exception) {
            if (isEmailUniqueConstraintViolation(exception)) {
                throw new ResourceConflictException(
                        "A user with this email address already exists.",
                        exception
                );
            }
            throw exception;
        }
    }

    /**
     * Retrieves a user belonging to the configured organization.
     *
     * @param userId user identifier
     * @return matching user information
     * @throws ResourceNotFoundException if the user is not found
     */
    @Transactional(readOnly = true)
    public UserResponse getUser(UUID userId) {
        UUID organizationId = getOrganizationId();

        User user = findUser(userId, organizationId);
        return toResponse(user);
    }

    /**
     * Lists users belonging to the configured organization.
     *
     * <p>Results include inactive and archived users so that
     * historical user records remain available. Repository
     * ordering is by last name, first name, and identifier.</p>
     *
     * @return organization-scoped user information
     */
    @Transactional(readOnly = true)
    public List<UserResponse> listUsers() {
        UUID organizationId = getOrganizationId();

        return userRepository
                .findByOrganizationIdOrderByLastNameAscFirstNameAscUserIdAsc(
                        organizationId
                )
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Updates permitted profile information for an existing user.
     *
     * <p>Email, organization ownership, lifecycle status,
     * and audit information are not editable through this
     * operation.</p>
     *
     * @param userId identifier of the user to update
     * @param request validated update information
     * @return updated user information
     * @throws ResourceNotFoundException if the user or a required
     *         reference is unavailable
     * @throws ResourceConflictException if the supervisor
     *         relationship is invalid
     */
    @Transactional
    public UserResponse updateUser(
            UUID userId,
            UpdateUserRequest request) {

        UUID organizationId = getOrganizationId();
        User user = findUser(userId, organizationId);

        if (user.isArchived()) {
            throw new ResourceConflictException(
                    "Archived users cannot be updated."
            );
        }

        validateAccountType(request.accountTypeId());
        validateDepartment(request.departmentId(), organizationId);
        validateSupervisor(
                request.supervisorUserId(),
                organizationId,
                userId
        );

        user.updateDetails(
                request.departmentId(),
                request.supervisorUserId(),
                request.accountTypeId(),
                request.employeeNumber(),
                request.firstName(),
                request.lastName()
        );

        return toResponse(userRepository.saveAndFlush(user));
    }

    /**
     * Returns the configured organization's identifier.
     *
     * @return organization identifier
     * @throws ResourceNotFoundException if initial setup is incomplete
     */
    private UUID getOrganizationId() {
        Organization organization = organizationRepository.findFirstBy()
                .orElseThrow(() -> new ResourceNotFoundException(
                        "The organization has not been configured."
                ));

        return organization.getOrganizationId();
    }

    /**
     * Finds a user within the configured organization.
     *
     * @param userId user identifier
     * @param organizationId organization identifier
     * @return matching user
     */
    private User findUser(UUID userId, UUID organizationId) {
        return userRepository
                .findByUserIdAndOrganizationId(userId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "The requested user was not found."
                ));
    }

    /**
     * Validates that an account type exists and is active.
     *
     * <p>System-managed account classifications cannot be
     * selected through ordinary user creation or updates.
     * They require a dedicated provisioning workflow.</p>
     *
     * @param accountTypeId requested account classification
     */
    private void validateAccountType(UUID accountTypeId) {
        AccountType accountType = accountTypeRepository
                .findByAccountTypeIdAndActiveTrue(accountTypeId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "The requested account type is unavailable."
                ));

        if (SYSTEM_ACCOUNT_TYPE.equals(accountType.getCode())) {
            throw new ResourceConflictException(
                    "System accounts require a dedicated provisioning workflow."
            );
        }
    }

    /**
     * Validates an optional department assignment.
     *
     * <p>The department must belong to the organization,
     * be active, and not be archived.</p>
     *
     * @param departmentId optional department identifier
     * @param organizationId owning organization identifier
     */
    private void validateDepartment(
            UUID departmentId,
            UUID organizationId) {

        if (departmentId == null) {
            return;
        }

        departmentRepository
                .findByDepartmentIdAndOrganizationIdAndActiveTrueAndArchivedFalse(
                        departmentId,
                        organizationId
                )
                .orElseThrow(() -> new ResourceNotFoundException(
                        "The requested department is unavailable."
                ));
    }

    /**
     * Validates an optional supervisor assignment.
     *
     * <p>The supervisor must belong to the organization,
     * be active, and not be archived. A user cannot be
     * assigned as their own supervisor.</p>
     *
     * @param supervisorUserId optional supervisor identifier
     * @param organizationId owning organization identifier
     * @param currentUserId identifier of the user being updated,
     *         or null during creation
     */
    private void validateSupervisor(
            UUID supervisorUserId,
            UUID organizationId,
            UUID currentUserId) {

        if (supervisorUserId == null) {
            return;
        }

        if (supervisorUserId.equals(currentUserId)) {
            throw new ResourceConflictException(
                    "A user cannot be their own supervisor."
            );
        }

        User supervisor = userRepository
                .findByUserIdAndOrganizationId(
                        supervisorUserId,
                        organizationId
                )
                .orElseThrow(() -> new ResourceNotFoundException(
                        "The requested supervisor was not found."
                ));

        if (!supervisor.isActive() || supervisor.isArchived()) {
            throw new ResourceConflictException(
                    "The supervisor must be active and not archived."
            );
        }
    }

    /**
     * Normalizes email for consistent storage and lookup.
     *
     * @param email validated email address
     * @return trimmed email in lowercase
     */
    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Determines whether PostgreSQL reported a violation of
     * the case-insensitive user email uniqueness index.
     *
     * @param exception persistence exception
     * @return true if the email uniqueness index was violated
     */
    private boolean isEmailUniqueConstraintViolation(
            DataIntegrityViolationException exception) {

        Throwable cause = exception;
        boolean found = false;

        while (cause != null && !found) {
            if (cause instanceof ConstraintViolationException constraintEx
                    && "23505".equals(constraintEx.getSQLState())
                    && EMAIL_UNIQUE_CONSTRAINT.equals(
                    constraintEx.getConstraintName())) {

                found = true;
            } else {
                cause = cause.getCause();
            }
        }

        return found;
    }


    /**
     * Converts a persistence entity to a response DTO.
     *
     * @param user user entity
     * @return response containing permitted user information
     */
    private UserResponse toResponse(User user) {
        return new UserResponse(
                user.getUserId(),
                user.getOrganizationId(),
                user.getDepartmentId(),
                user.getSupervisorUserId(),
                user.getAccountTypeId(),
                user.getEmployeeNumber(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                user.isActive(),
                user.isArchived(),
                user.getCreatedAt(),
                user.getUpdatedAt(),
                user.getVersion()
        );
    }
}
