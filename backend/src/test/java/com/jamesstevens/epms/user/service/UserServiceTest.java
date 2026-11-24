package com.jamesstevens.epms.user.service;

import com.jamesstevens.epms.common.exception.ResourceConflictException;
import com.jamesstevens.epms.common.exception.ResourceNotFoundException;
import com.jamesstevens.epms.organization.dto.CreateOrganizationRequest;
import com.jamesstevens.epms.organization.service.OrganizationService;
import com.jamesstevens.epms.user.dto.UpdateUserRequest;
import com.jamesstevens.epms.user.dto.CreateUserRequest;
import com.jamesstevens.epms.user.dto.UserResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PostgreSQL-backed integration tests for UserService.
 *
 * <p>Tests execute against a PostgreSQL 18 container with
 * the application's Flyway migrations applied.</p>
 */
@SpringBootTest
@Testcontainers
class UserServiceTest {

    private static final UUID HUMAN_ACCOUNT_TYPE_ID =
            UUID.fromString(
                    "0199a000-0000-7000-8000-000000000001"
            );

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:18")
                    .withInitScript("test-db-init.sql");

    @Autowired
    private UserService userService;

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    /**
     * Verifies that a user cannot be created before
     * the organization has been configured.
     */
    @Test
    @Transactional
    void rejectsUserCreationBeforeOrganizationSetup() {

        CreateUserRequest request = new CreateUserRequest(
                null,
                null,
                HUMAN_ACCOUNT_TYPE_ID,
                null,
                "Test",
                "User",
                "test.user@example.com"
        );

        assertThrows(
                ResourceNotFoundException.class,
                () -> userService.createUser(request)
        );
    }

    /**
     * Verifies that a valid human user can be created
     * within the configured organization.
     *
     * <p>Also verifies generated identifiers, default
     * lifecycle values, timestamps, and versioning.</p>
     */
    @Test
    @Transactional
    void createsUserWithValidAccountType() {

        var organization = organizationService.createOrganization(
                new CreateOrganizationRequest(
                        "EPMS Test Organization",
                        "User creation integration test"
                )
        );

        CreateUserRequest request = new CreateUserRequest(
                null,
                null,
                HUMAN_ACCOUNT_TYPE_ID,
                "EMP-001",
                "Jane",
                "Doe",
                "Jane.Doe@Example.com"
        );

        UserResponse response = userService.createUser(request);

        assertNotNull(response.userId());
        assertEquals(7, response.userId().version());

        assertEquals(
                organization.organizationId(),
                response.organizationId()
        );

        assertEquals(
                HUMAN_ACCOUNT_TYPE_ID,
                response.accountTypeId()
        );

        assertNull(response.departmentId());
        assertNull(response.supervisorUserId());

        assertEquals("EMP-001", response.employeeNumber());
        assertEquals("Jane", response.firstName());
        assertEquals("Doe", response.lastName());

        assertEquals(
                "jane.doe@example.com",
                response.email()
        );

        assertTrue(response.isActive());
        assertFalse(response.isArchived());

        assertNotNull(response.createdAt());
        assertNotNull(response.updatedAt());

        assertEquals(0L, response.version());
    }

    /**
     * Verifies that email uniqueness is case-insensitive
     * within the configured organization.
     *
     * <p>The service must reject a second user whose email
     * differs only in capitalization.</p>
     */
    @Test
    @Transactional
    void rejectsDuplicateEmailIgnoringCase() {

        organizationService.createOrganization(
                new CreateOrganizationRequest(
                        "EPMS Test Organization",
                        "Email uniqueness integration test"
                )
        );

        CreateUserRequest firstRequest = new CreateUserRequest(
                null,
                null,
                HUMAN_ACCOUNT_TYPE_ID,
                "EMP-001",
                "Jane",
                "Doe",
                "jane.doe@example.com"
        );

        userService.createUser(firstRequest);

        CreateUserRequest duplicateRequest = new CreateUserRequest(
                null,
                null,
                HUMAN_ACCOUNT_TYPE_ID,
                "EMP-002",
                "Janet",
                "Doe",
                "JANE.DOE@EXAMPLE.COM"
        );

        assertThrows(
                ResourceConflictException.class,
                () -> userService.createUser(duplicateRequest)
        );
    }

    /**
     * Verifies that an active, non-archived user
     * can supervise another user in the organization.
     */
    @Test
    @Transactional
    void createsUserWithEligibleSupervisor() {

        organizationService.createOrganization(
                new CreateOrganizationRequest(
                        "EPMS Test Organization",
                        "Supervisor eligibility integration test"
                )
        );

        CreateUserRequest supervisorRequest = new CreateUserRequest(
                null,
                null,
                HUMAN_ACCOUNT_TYPE_ID,
                "EMP-001",
                "Sarah",
                "Manager",
                "sarah.manager@example.com"
        );

        UserResponse supervisor =
                userService.createUser(supervisorRequest);

        CreateUserRequest employeeRequest = new CreateUserRequest(
                null,
                supervisor.userId(),
                HUMAN_ACCOUNT_TYPE_ID,
                "EMP-002",
                "Michael",
                "Employee",
                "michael.employee@example.com"
        );

        UserResponse employee =
                userService.createUser(employeeRequest);

        assertEquals(
                supervisor.userId(),
                employee.supervisorUserId()
        );

        assertNotEquals(
                supervisor.userId(),
                employee.userId()
        );

        assertEquals(
                supervisor.organizationId(),
                employee.organizationId()
        );

        assertTrue(supervisor.isActive());
        assertFalse(supervisor.isArchived());
    }

    /**
     * Verifies that a user cannot be assigned
     * to a supervisor who does not exist.
     */
    @Test
    @Transactional
    void rejectsNonexistentSupervisor() {

        organizationService.createOrganization(
                new CreateOrganizationRequest(
                        "EPMS Test Organization",
                        "Nonexistent supervisor integration test"
                )
        );

        UUID nonexistentSupervisorId = UUID.randomUUID();

        CreateUserRequest request = new CreateUserRequest(
                null,
                nonexistentSupervisorId,
                HUMAN_ACCOUNT_TYPE_ID,
                "EMP-003",
                "Emily",
                "Employee",
                "emily.employee@example.com"
        );

        ResourceNotFoundException exception = assertThrows(
                ResourceNotFoundException.class,
                () -> userService.createUser(request)
        );

        assertEquals(
                "The requested supervisor was not found.",
                exception.getMessage()
        );
    }

    /**
     * Verifies that an existing user cannot
     * be assigned as their own supervisor.
     */
    @Test
    @Transactional
    void rejectsSelfSupervision() {

        organizationService.createOrganization(
                new CreateOrganizationRequest(
                        "EPMS Test Organization",
                        "Self-supervision integration test"
                )
        );

        CreateUserRequest createRequest = new CreateUserRequest(
                null,
                null,
                HUMAN_ACCOUNT_TYPE_ID,
                "EMP-004",
                "Alex",
                "Johnson",
                "alex.johnson@example.com"
        );

        UserResponse user = userService.createUser(createRequest);

        UpdateUserRequest updateRequest = new UpdateUserRequest(
                null,
                user.userId(),
                HUMAN_ACCOUNT_TYPE_ID,
                "EMP-004",
                "Alex",
                "Johnson"
        );

        ResourceConflictException exception = assertThrows(
                ResourceConflictException.class,
                () -> userService.updateUser(
                        user.userId(),
                        updateRequest
                )
        );

        assertEquals(
                "A user cannot be their own supervisor.",
                exception.getMessage()
        );
    }

    /**
     * Verifies that an inactive user cannot
     * be assigned as another user's supervisor.
     */
    @Test
    @Transactional
    void rejectsInactiveSupervisor() {

        organizationService.createOrganization(
                new CreateOrganizationRequest(
                        "EPMS Test Organization",
                        "Inactive supervisor integration test"
                )
        );

        UserResponse supervisor = userService.createUser(
                new CreateUserRequest(
                        null,
                        null,
                        HUMAN_ACCOUNT_TYPE_ID,
                        "EMP-010",
                        "Inactive",
                        "Manager",
                        "inactive.manager@example.com"
                )
        );

        jdbcTemplate.update(
                "UPDATE users SET is_active = FALSE WHERE user_id = ?",
                supervisor.userId()
        );

        entityManager.clear();

        CreateUserRequest employeeRequest = new CreateUserRequest(
                null,
                supervisor.userId(),
                HUMAN_ACCOUNT_TYPE_ID,
                "EMP-011",
                "Test",
                "Employee",
                "employee.inactive@example.com"
        );

        ResourceConflictException exception = assertThrows(
                ResourceConflictException.class,
                () -> userService.createUser(employeeRequest)
        );

        assertEquals(
                "The supervisor must be active and not archived.",
                exception.getMessage()
        );
    }

    /**
     * Verifies that an archived user cannot
     * be assigned as another user's supervisor.
     */
    @Test
    @Transactional
    void rejectsArchivedSupervisor() {

        organizationService.createOrganization(
                new CreateOrganizationRequest(
                        "EPMS Test Organization",
                        "Archived supervisor integration test"
                )
        );

        UserResponse supervisor = userService.createUser(
                new CreateUserRequest(
                        null,
                        null,
                        HUMAN_ACCOUNT_TYPE_ID,
                        "EMP-020",
                        "Archived",
                        "Manager",
                        "archived.manager@example.com"
                )
        );

        jdbcTemplate.update(
                "UPDATE users SET is_archived = TRUE WHERE user_id = ?",
                supervisor.userId()
        );

        entityManager.clear();

        CreateUserRequest employeeRequest = new CreateUserRequest(
                null,
                supervisor.userId(),
                HUMAN_ACCOUNT_TYPE_ID,
                "EMP-021",
                "Test",
                "Employee",
                "employee.archived@example.com"
        );

        ResourceConflictException exception = assertThrows(
                ResourceConflictException.class,
                () -> userService.createUser(employeeRequest)
        );

        assertEquals(
                "The supervisor must be active and not archived.",
                exception.getMessage()
        );
    }

    /**
     * Verifies that a user cannot be created
     * with an account type that does not exist.
     */
    @Test
    @Transactional
    void rejectsNonexistentAccountType() {

        organizationService.createOrganization(
                new CreateOrganizationRequest(
                        "EPMS Test Organization",
                        "Account type validation integration test"
                )
        );

        UUID nonexistentAccountTypeId = UUID.randomUUID();

        CreateUserRequest request = new CreateUserRequest(
                null,
                null,
                nonexistentAccountTypeId,
                "EMP-030",
                "Invalid",
                "AccountType",
                "invalid.accounttype@example.com"
        );

        ResourceNotFoundException exception = assertThrows(
                ResourceNotFoundException.class,
                () -> userService.createUser(request)
        );

        assertEquals(
                "The requested account type is unavailable.",
                exception.getMessage()
        );
    }

    /**
     * Verifies that an inactive account type
     * cannot be assigned to a new user.
     */
    @Test
    @Transactional
    void rejectsInactiveAccountType() {

        organizationService.createOrganization(
                new CreateOrganizationRequest(
                        "EPMS Test Organization",
                        "Inactive account type integration test"
                )
        );

        UUID serviceAccountTypeId = UUID.fromString(
                "0199a000-0000-7000-8000-000000000002"
        );

        jdbcTemplate.update(
                """
                UPDATE account_types
                SET is_active = FALSE
                WHERE account_type_id = ?
                """,
                serviceAccountTypeId
        );

        entityManager.clear();

        CreateUserRequest request = new CreateUserRequest(
                null,
                null,
                serviceAccountTypeId,
                "EMP-040",
                "Inactive",
                "AccountType",
                "inactive.accounttype@example.com"
        );

        ResourceNotFoundException exception = assertThrows(
                ResourceNotFoundException.class,
                () -> userService.createUser(request)
        );

        assertEquals(
                "The requested account type is unavailable.",
                exception.getMessage()
        );
    }

    /**
     * Verifies that SYSTEM accounts cannot be created
     * through the standard user creation workflow.
     */
    @Test
    @Transactional
    void rejectsSystemAccountCreation() {

        organizationService.createOrganization(
                new CreateOrganizationRequest(
                        "EPMS Test Organization",
                        "System account restriction integration test"
                )
        );

        UUID systemAccountTypeId = UUID.fromString(
                "0199a000-0000-7000-8000-000000000003"
        );

        CreateUserRequest request = new CreateUserRequest(
                null,
                null,
                systemAccountTypeId,
                "SYS-001",
                "EPMS",
                "System",
                "system@example.com"
        );

        ResourceConflictException exception = assertThrows(
                ResourceConflictException.class,
                () -> userService.createUser(request)
        );

        assertEquals(
                "System accounts require a dedicated provisioning workflow.",
                exception.getMessage()
        );
    }

    /**
     * Verifies that a user cannot be assigned
     * to a department that does not exist.
     */
    @Test
    @Transactional
    void rejectsNonexistentDepartment() {

        organizationService.createOrganization(
                new CreateOrganizationRequest(
                        "EPMS Test Organization",
                        "Nonexistent department integration test"
                )
        );

        UUID nonexistentDepartmentId = UUID.randomUUID();

        CreateUserRequest request = new CreateUserRequest(
                nonexistentDepartmentId,
                null,
                HUMAN_ACCOUNT_TYPE_ID,
                "EMP-050",
                "Daniel",
                "Employee",
                "daniel.employee@example.com"
        );

        ResourceNotFoundException exception = assertThrows(
                ResourceNotFoundException.class,
                () -> userService.createUser(request)
        );

        assertEquals(
                "The requested department is unavailable.",
                exception.getMessage()
        );
    }

    /**
     * Verifies that a user cannot be assigned
     * to an inactive department.
     */
    @Test
    @Transactional
    void rejectsInactiveDepartment() {

        var organization = organizationService.createOrganization(
                new CreateOrganizationRequest(
                        "EPMS Test Organization",
                        "Inactive department integration test"
                )
        );

        UUID departmentId = jdbcTemplate.queryForObject(
                """
                INSERT INTO departments (
                    organization_id,
                    name,
                    is_active
                )
                VALUES (?, ?, FALSE)
                RETURNING department_id
                """,
                UUID.class,
                organization.organizationId(),
                "Inactive Department"
        );

        entityManager.clear();

        CreateUserRequest request = new CreateUserRequest(
                departmentId,
                null,
                HUMAN_ACCOUNT_TYPE_ID,
                "EMP-060",
                "Inactive",
                "DepartmentUser",
                "inactive.department@example.com"
        );

        ResourceNotFoundException exception = assertThrows(
                ResourceNotFoundException.class,
                () -> userService.createUser(request)
        );

        assertEquals(
                "The requested department is unavailable.",
                exception.getMessage()
        );
    }

    /**
     * Verifies that a user cannot be assigned
     * to an archived department.
     */
    @Test
    @Transactional
    void rejectsArchivedDepartment() {

        var organization = organizationService.createOrganization(
                new CreateOrganizationRequest(
                        "EPMS Test Organization",
                        "Archived department integration test"
                )
        );

        UUID departmentId = jdbcTemplate.queryForObject(
                """
                INSERT INTO departments (
                    organization_id,
                    name,
                    is_active,
                    is_archived,
                    archived_at,
                    archive_reason
                )
                VALUES (?, ?, TRUE, TRUE, CURRENT_TIMESTAMP, ?)
                RETURNING department_id
                """,
                UUID.class,
                organization.organizationId(),
                "Archived Department",
                "Department retired for integration testing"
        );

        entityManager.clear();

        CreateUserRequest request = new CreateUserRequest(
                departmentId,
                null,
                HUMAN_ACCOUNT_TYPE_ID,
                "EMP-070",
                "Archived",
                "DepartmentUser",
                "archived.department@example.com"
        );

        ResourceNotFoundException exception = assertThrows(
                ResourceNotFoundException.class,
                () -> userService.createUser(request)
        );

        assertEquals(
                "The requested department is unavailable.",
                exception.getMessage()
        );
    }

    /**
     * Verifies that a user can be assigned
     * to an active, nonarchived department.
     */
    @Test
    @Transactional
    void createsUserWithValidDepartment() {

        var organization = organizationService.createOrganization(
                new CreateOrganizationRequest(
                        "EPMS Test Organization",
                        "Valid department assignment integration test"
                )
        );

        UUID departmentId = jdbcTemplate.queryForObject(
                """
                INSERT INTO departments (
                    organization_id,
                    name,
                    is_active,
                    is_archived
                )
                VALUES (?, ?, TRUE, FALSE)
                RETURNING department_id
                """,
                UUID.class,
                organization.organizationId(),
                "Engineering"
        );

        entityManager.clear();

        CreateUserRequest request = new CreateUserRequest(
                departmentId,
                null,
                HUMAN_ACCOUNT_TYPE_ID,
                "EMP-080",
                "Emily",
                "Engineer",
                "emily.engineer@example.com"
        );

        UserResponse response = userService.createUser(request);

        assertNotNull(response.userId());
        assertEquals(
                organization.organizationId(),
                response.organizationId()
        );
        assertEquals(
                departmentId,
                response.departmentId()
        );
        assertEquals(
                HUMAN_ACCOUNT_TYPE_ID,
                response.accountTypeId()
        );
        assertEquals("Emily", response.firstName());
        assertEquals("Engineer", response.lastName());
        assertEquals(
                "emily.engineer@example.com",
                response.email()
        );
        assertTrue(response.isActive());
        assertFalse(response.isArchived());
    }

    /**
     * Verifies that an existing user can be
     * retrieved using their unique identifier.
     */
    @Test
    @Transactional
    void retrievesExistingUser() {

        organizationService.createOrganization(
                new CreateOrganizationRequest(
                        "EPMS Test Organization",
                        "User retrieval integration test"
                )
        );

        CreateUserRequest request = new CreateUserRequest(
                null,
                null,
                HUMAN_ACCOUNT_TYPE_ID,
                "EMP-090",
                "Michael",
                "Anderson",
                "michael.anderson@example.com"
        );

        UserResponse createdUser = userService.createUser(request);

        entityManager.clear();

        UserResponse retrievedUser = userService.getUser(
                createdUser.userId()
        );

        assertEquals(createdUser.userId(), retrievedUser.userId());
        assertEquals(createdUser.organizationId(), retrievedUser.organizationId());
        assertEquals(createdUser.accountTypeId(), retrievedUser.accountTypeId());
        assertEquals("EMP-090", retrievedUser.employeeNumber());
        assertEquals("Michael", retrievedUser.firstName());
        assertEquals("Anderson", retrievedUser.lastName());
        assertEquals(
                "michael.anderson@example.com",
                retrievedUser.email()
        );
        assertTrue(retrievedUser.isActive());
        assertFalse(retrievedUser.isArchived());
    }

    /**
     * Verifies that retrieving a nonexistent user
     * produces a resource-not-found exception.
     */
    @Test
    @Transactional
    void rejectsNonexistentUserRetrieval() {

        organizationService.createOrganization(
                new CreateOrganizationRequest(
                        "EPMS Test Organization",
                        "Nonexistent user retrieval integration test"
                )
        );

        UUID nonexistentUserId = UUID.randomUUID();

        ResourceNotFoundException exception = assertThrows(
                ResourceNotFoundException.class,
                () -> userService.getUser(nonexistentUserId)
        );

        assertEquals(
                "The requested user was not found.",
                exception.getMessage()
        );
    }

    /**
     * Verifies that listing users returns an empty
     * collection when no users have been created.
     */
    @Test
    @Transactional
    void listsNoUsersWhenOrganizationIsEmpty() {

        organizationService.createOrganization(
                new CreateOrganizationRequest(
                        "EPMS Test Organization",
                        "Empty user list integration test"
                )
        );

        List<UserResponse> users = userService.listUsers();

        assertNotNull(users);
        assertTrue(users.isEmpty());
    }

    /**
     * Verifies that listing users returns
     * all users in the configured organization.
     */
    @Test
    @Transactional
    void listsMultipleUsers() {

        organizationService.createOrganization(
                new CreateOrganizationRequest(
                        "EPMS Test Organization",
                        "Multiple user listing integration test"
                )
        );

        UserResponse firstUser = userService.createUser(
                new CreateUserRequest(
                        null,
                        null,
                        HUMAN_ACCOUNT_TYPE_ID,
                        "EMP-100",
                        "Alice",
                        "Brown",
                        "alice.brown@example.com"
                )
        );

        UserResponse secondUser = userService.createUser(
                new CreateUserRequest(
                        null,
                        null,
                        HUMAN_ACCOUNT_TYPE_ID,
                        "EMP-101",
                        "Robert",
                        "Smith",
                        "robert.smith@example.com"
                )
        );

        entityManager.clear();

        List<UserResponse> users = userService.listUsers();

        assertEquals(2, users.size());

        assertTrue(
                users.stream().anyMatch(
                        user -> user.userId().equals(firstUser.userId())
                )
        );

        assertTrue(
                users.stream().anyMatch(
                        user -> user.userId().equals(secondUser.userId())
                )
        );
    }

    /**
     * Verifies that users are listed alphabetically
     * by last name and then first name.
     */
    @Test
    @Transactional
    void listsUsersInAlphabeticalOrder() {

        organizationService.createOrganization(
                new CreateOrganizationRequest(
                        "EPMS Test Organization",
                        "User sorting integration test"
                )
        );

        userService.createUser(
                new CreateUserRequest(
                        null, null, HUMAN_ACCOUNT_TYPE_ID,
                        "EMP-110", "Zachary", "Smith",
                        "zachary.smith@example.com"
                )
        );

        userService.createUser(
                new CreateUserRequest(
                        null, null, HUMAN_ACCOUNT_TYPE_ID,
                        "EMP-111", "Brian", "Anderson",
                        "brian.anderson@example.com"
                )
        );

        userService.createUser(
                new CreateUserRequest(
                        null, null, HUMAN_ACCOUNT_TYPE_ID,
                        "EMP-112", "Alice", "Smith",
                        "alice.smith@example.com"
                )
        );

        userService.createUser(
                new CreateUserRequest(
                        null, null, HUMAN_ACCOUNT_TYPE_ID,
                        "EMP-113", "Alice", "Anderson",
                        "alice.anderson@example.com"
                )
        );

        entityManager.clear();

        List<UserResponse> users = userService.listUsers();

        assertEquals(4, users.size());

        assertEquals("alice.anderson@example.com", users.get(0).email());
        assertEquals("brian.anderson@example.com", users.get(1).email());
        assertEquals("alice.smith@example.com", users.get(2).email());
        assertEquals("zachary.smith@example.com", users.get(3).email());
    }

    /**
     * Verifies that an existing user's editable details
     * can be updated without changing their identity or email.
     */
    @Test
    @Transactional
    void updatesExistingUser() {

        organizationService.createOrganization(
                new CreateOrganizationRequest(
                        "EPMS Test Organization",
                        "Successful user update integration test"
                )
        );

        UserResponse created = userService.createUser(
                new CreateUserRequest(
                        null, null, HUMAN_ACCOUNT_TYPE_ID,
                        "EMP-120", "John", "Smith",
                        "john.smith@example.com"
                )
        );

        UpdateUserRequest request = new UpdateUserRequest(
                null,
                null,
                HUMAN_ACCOUNT_TYPE_ID,
                "EMP-121",
                "Jonathan",
                "Stevens"
        );

        UserResponse updated = userService.updateUser(
                created.userId(), request
        );

        assertEquals(created.userId(), updated.userId());
        assertEquals(created.organizationId(), updated.organizationId());
        assertEquals(created.email(), updated.email());
        assertEquals("EMP-121", updated.employeeNumber());
        assertEquals("Jonathan", updated.firstName());
        assertEquals("Stevens", updated.lastName());
        assertEquals(created.version() + 1, updated.version());
    }

    /**
     * Verifies that updating a nonexistent user
     * produces a resource-not-found exception.
     */
    @Test
    @Transactional
    void rejectsNonexistentUserUpdate() {

        organizationService.createOrganization(
                new CreateOrganizationRequest(
                        "EPMS Test Organization",
                        "Nonexistent user update integration test"
                )
        );

        UpdateUserRequest request = new UpdateUserRequest(
                null, null, HUMAN_ACCOUNT_TYPE_ID,
                "EMP-130", "Missing", "Employee"
        );

        ResourceNotFoundException exception = assertThrows(
                ResourceNotFoundException.class,
                () -> userService.updateUser(UUID.randomUUID(), request)
        );

        assertEquals(
                "The requested user was not found.",
                exception.getMessage()
        );
    }

    /**
     * Verifies that a user cannot assign themselves
     * as their own supervisor during an update.
     */
    @Test
    @Transactional
    void rejectsSelfSupervisionDuringUpdate() {

        organizationService.createOrganization(
                new CreateOrganizationRequest(
                        "EPMS Test Organization",
                        "Self-supervision update integration test"
                )
        );

        UserResponse created = userService.createUser(
                new CreateUserRequest(
                        null, null, HUMAN_ACCOUNT_TYPE_ID,
                        "EMP-140", "Sarah", "Manager",
                        "sarah.manager@example.com"
                )
        );

        UpdateUserRequest request = new UpdateUserRequest(
                null,
                created.userId(),
                HUMAN_ACCOUNT_TYPE_ID,
                "EMP-140",
                "Sarah",
                "Manager"
        );

        ResourceConflictException exception = assertThrows(
                ResourceConflictException.class,
                () -> userService.updateUser(created.userId(), request)
        );

        assertEquals(
                "A user cannot be their own supervisor.",
                exception.getMessage()
        );
    }

    /**
     * Verifies that archived users cannot
     * have their details modified.
     */
    @Test
    @Transactional
    void rejectsArchivedUserUpdate() {

        organizationService.createOrganization(
                new CreateOrganizationRequest(
                        "EPMS Test Organization",
                        "Archived user update integration test"
                )
        );

        UserResponse created = userService.createUser(
                new CreateUserRequest(
                        null, null, HUMAN_ACCOUNT_TYPE_ID,
                        "EMP-150", "David", "Employee",
                        "david.employee@example.com"
                )
        );

        jdbcTemplate.update(
                """
                UPDATE users
                SET is_archived = TRUE,
                    archived_at = CURRENT_TIMESTAMP
                WHERE user_id = ?
                """,
                created.userId()
        );

        entityManager.clear();

        UpdateUserRequest request = new UpdateUserRequest(
                null, null, HUMAN_ACCOUNT_TYPE_ID,
                "EMP-151", "David", "Updated"
        );

        ResourceConflictException exception = assertThrows(
                ResourceConflictException.class,
                () -> userService.updateUser(created.userId(), request)
        );

        assertEquals(
                "Archived users cannot be updated.",
                exception.getMessage()
        );
    }

    /**
     * Verifies that a user cannot be updated
     * with an account type that does not exist.
     */
    @Test
    @Transactional
    void rejectsNonexistentAccountTypeDuringUpdate() {

        organizationService.createOrganization(
                new CreateOrganizationRequest(
                        "EPMS Test Organization",
                        "Invalid account type update test"
                )
        );

        UserResponse created = userService.createUser(
                new CreateUserRequest(
                        null, null, HUMAN_ACCOUNT_TYPE_ID,
                        "EMP-160", "Alice", "Employee",
                        "alice.employee@example.com"
                )
        );

        UpdateUserRequest request = new UpdateUserRequest(
                null, null, UUID.randomUUID(),
                "EMP-160", "Alice", "Employee"
        );

        ResourceNotFoundException exception = assertThrows(
                ResourceNotFoundException.class,
                () -> userService.updateUser(created.userId(), request)
        );

        assertEquals(
                "The requested account type is unavailable.",
                exception.getMessage()
        );
    }

    /**
     * Verifies that a user cannot be updated
     * with a department that does not exist.
     */
    @Test
    @Transactional
    void rejectsNonexistentDepartmentDuringUpdate() {

        organizationService.createOrganization(
                new CreateOrganizationRequest(
                        "EPMS Test Organization",
                        "Invalid department update test"
                )
        );

        UserResponse created = userService.createUser(
                new CreateUserRequest(
                        null, null, HUMAN_ACCOUNT_TYPE_ID,
                        "EMP-170", "Brian", "Employee",
                        "brian.employee@example.com"
                )
        );

        UpdateUserRequest request = new UpdateUserRequest(
                UUID.randomUUID(), null, HUMAN_ACCOUNT_TYPE_ID,
                "EMP-170", "Brian", "Employee"
        );

        ResourceNotFoundException exception = assertThrows(
                ResourceNotFoundException.class,
                () -> userService.updateUser(created.userId(), request)
        );

        assertEquals(
                "The requested department is unavailable.",
                exception.getMessage()
        );
    }

    /**
     * Verifies that an update cannot assign
     * a supervisor that does not exist.
     */
    @Test
    @Transactional
    void rejectsNonexistentSupervisorDuringUpdate() {

        organizationService.createOrganization(
                new CreateOrganizationRequest(
                        "EPMS Test Organization",
                        "Invalid supervisor update test"
                )
        );

        UserResponse created = userService.createUser(
                new CreateUserRequest(
                        null, null, HUMAN_ACCOUNT_TYPE_ID,
                        "EMP-180", "Carol", "Employee",
                        "carol.employee@example.com"
                )
        );

        UpdateUserRequest request = new UpdateUserRequest(
                null, UUID.randomUUID(), HUMAN_ACCOUNT_TYPE_ID,
                "EMP-180", "Carol", "Employee"
        );

        ResourceNotFoundException exception = assertThrows(
                ResourceNotFoundException.class,
                () -> userService.updateUser(created.userId(), request)
        );

        assertEquals(
                "The requested supervisor was not found.",
                exception.getMessage()
        );
    }

    /**
     * Verifies that the SYSTEM account type
     * cannot be assigned through normal user updates.
     */
    @Test
    @Transactional
    void rejectsSystemAccountTypeDuringUpdate() {

        organizationService.createOrganization(
                new CreateOrganizationRequest(
                        "EPMS Test Organization",
                        "System account update restriction test"
                )
        );

        UserResponse created = userService.createUser(
                new CreateUserRequest(
                        null, null, HUMAN_ACCOUNT_TYPE_ID,
                        "EMP-190", "David", "Employee",
                        "david.employee@example.com"
                )
        );

        UUID systemAccountTypeId = UUID.fromString(
                "0199a000-0000-7000-8000-000000000003"
        );

        UpdateUserRequest request = new UpdateUserRequest(
                null, null, systemAccountTypeId,
                "EMP-190", "David", "Employee"
        );

        ResourceConflictException exception = assertThrows(
                ResourceConflictException.class,
                () -> userService.updateUser(created.userId(), request)
        );

        assertEquals(
                "System accounts require a dedicated provisioning workflow.",
                exception.getMessage()
        );
    }

    /**
     * Verifies that an inactive user cannot
     * become another user's supervisor during an update.
     */
    @Test
    @Transactional
    void rejectsInactiveSupervisorDuringUpdate() {

        organizationService.createOrganization(
                new CreateOrganizationRequest(
                        "EPMS Test Organization",
                        "Inactive supervisor update test"
                )
        );

        UserResponse supervisor = userService.createUser(
                new CreateUserRequest(
                        null, null, HUMAN_ACCOUNT_TYPE_ID,
                        "EMP-200", "Helen", "Supervisor",
                        "helen.supervisor@example.com"
                )
        );

        UserResponse employee = userService.createUser(
                new CreateUserRequest(
                        null, null, HUMAN_ACCOUNT_TYPE_ID,
                        "EMP-201", "Peter", "Employee",
                        "peter.employee@example.com"
                )
        );

        jdbcTemplate.update(
                "UPDATE users SET is_active = FALSE WHERE user_id = ?",
                supervisor.userId()
        );

        entityManager.clear();

        UpdateUserRequest request = new UpdateUserRequest(
                null, supervisor.userId(), HUMAN_ACCOUNT_TYPE_ID,
                "EMP-201", "Peter", "Employee"
        );

        ResourceConflictException exception = assertThrows(
                ResourceConflictException.class,
                () -> userService.updateUser(employee.userId(), request)
        );

        assertEquals(
                "The supervisor must be active and not archived.",
                exception.getMessage()
        );
    }

    /**
     * Verifies that an archived user cannot
     * become another user's supervisor during an update.
     */
    @Test
    @Transactional
    void rejectsArchivedSupervisorDuringUpdate() {

        organizationService.createOrganization(
                new CreateOrganizationRequest(
                        "EPMS Test Organization",
                        "Archived supervisor update test"
                )
        );

        UserResponse supervisor = userService.createUser(
                new CreateUserRequest(
                        null, null, HUMAN_ACCOUNT_TYPE_ID,
                        "EMP-210", "Helen", "Supervisor",
                        "helen.archived@example.com"
                )
        );

        UserResponse employee = userService.createUser(
                new CreateUserRequest(
                        null, null, HUMAN_ACCOUNT_TYPE_ID,
                        "EMP-211", "Peter", "Employee",
                        "peter.archived@example.com"
                )
        );

        jdbcTemplate.update(
                """
                UPDATE users
                SET is_archived = TRUE,
                    archived_at = CURRENT_TIMESTAMP
                WHERE user_id = ?
                """,
                supervisor.userId()
        );

        entityManager.clear();

        UpdateUserRequest request = new UpdateUserRequest(
                null, supervisor.userId(), HUMAN_ACCOUNT_TYPE_ID,
                "EMP-211", "Peter", "Employee"
        );

        ResourceConflictException exception = assertThrows(
                ResourceConflictException.class,
                () -> userService.updateUser(employee.userId(), request)
        );

        assertEquals(
                "The supervisor must be active and not archived.",
                exception.getMessage()
        );
    }

    /**
     * Verifies that users cannot be reassigned
     * to an inactive department.
     */
    @Test
    @Transactional
    void rejectsInactiveDepartmentDuringUpdate() {

        var organization = organizationService.createOrganization(
                new CreateOrganizationRequest(
                        "EPMS Test Organization",
                        "Inactive department update test"
                )
        );

        UserResponse employee = userService.createUser(
                new CreateUserRequest(
                        null, null, HUMAN_ACCOUNT_TYPE_ID,
                        "EMP-220", "Laura", "Employee",
                        "laura.employee@example.com"
                )
        );

        UUID departmentId = jdbcTemplate.queryForObject(
                """
                INSERT INTO departments (
                    organization_id, name, is_active
                )
                VALUES (?, ?, FALSE)
                RETURNING department_id
                """,
                UUID.class,
                organization.organizationId(),
                "Inactive Engineering"
        );

        entityManager.clear();

        UpdateUserRequest request = new UpdateUserRequest(
                departmentId, null, HUMAN_ACCOUNT_TYPE_ID,
                "EMP-220", "Laura", "Employee"
        );

        ResourceNotFoundException exception = assertThrows(
                ResourceNotFoundException.class,
                () -> userService.updateUser(employee.userId(), request)
        );

        assertEquals(
                "The requested department is unavailable.",
                exception.getMessage()
        );
    }

    /**
     * Verifies that users cannot be reassigned
     * to an archived department.
     */
    @Test
    @Transactional
    void rejectsArchivedDepartmentDuringUpdate() {

        var organization = organizationService.createOrganization(
                new CreateOrganizationRequest(
                        "EPMS Test Organization",
                        "Archived department update test"
                )
        );

        UserResponse employee = userService.createUser(
                new CreateUserRequest(
                        null, null, HUMAN_ACCOUNT_TYPE_ID,
                        "EMP-230", "Laura", "Employee",
                        "laura.archived@example.com"
                )
        );

        UUID departmentId = jdbcTemplate.queryForObject(
                """
                INSERT INTO departments (
                    organization_id,
                    name,
                    is_active,
                    is_archived,
                    archived_at,
                    archive_reason
                )
                VALUES (?, ?, TRUE, TRUE, CURRENT_TIMESTAMP, ?)
                RETURNING department_id
                """,
                UUID.class,
                organization.organizationId(),
                "Archived Engineering",
                "Department retired for integration testing"
        );

        entityManager.clear();

        UpdateUserRequest request = new UpdateUserRequest(
                departmentId, null, HUMAN_ACCOUNT_TYPE_ID,
                "EMP-230", "Laura", "Employee"
        );

        ResourceNotFoundException exception = assertThrows(
                ResourceNotFoundException.class,
                () -> userService.updateUser(employee.userId(), request)
        );

        assertEquals(
                "The requested department is unavailable.",
                exception.getMessage()
        );
    }

    @Test
    @Transactional
    void updatesUserWithEligibleSupervisor() {

        organizationService.createOrganization(
                new CreateOrganizationRequest(
                        "EPMS Test Organization",
                        "Supervisor reassignment integration test"
                )
        );

        UserResponse supervisor = userService.createUser(
                new CreateUserRequest(
                        null, null, HUMAN_ACCOUNT_TYPE_ID,
                        "EMP-240", "Helen", "Manager",
                        "helen.manager@example.com"
                )
        );

        UserResponse employee = userService.createUser(
                new CreateUserRequest(
                        null, null, HUMAN_ACCOUNT_TYPE_ID,
                        "EMP-241", "Peter", "Employee",
                        "peter.employee@example.com"
                )
        );

        UpdateUserRequest request = new UpdateUserRequest(
                null,
                supervisor.userId(),
                HUMAN_ACCOUNT_TYPE_ID,
                "EMP-241",
                "Peter",
                "Employee"
        );

        UserResponse updated = userService.updateUser(
                employee.userId(), request
        );

        assertEquals(supervisor.userId(), updated.supervisorUserId());
        assertEquals(employee.userId(), updated.userId());
        assertEquals(employee.email(), updated.email());
    }

    @Test
    @Transactional
    void updatesUserWithEligibleDepartment() {

        var organization = organizationService.createOrganization(
                new CreateOrganizationRequest(
                        "EPMS Test Organization",
                        "Department reassignment integration test"
                )
        );

        UserResponse employee = userService.createUser(
                new CreateUserRequest(
                        null, null, HUMAN_ACCOUNT_TYPE_ID,
                        "EMP-250", "Laura", "Employee",
                        "laura.department@example.com"
                )
        );

        UUID departmentId = jdbcTemplate.queryForObject(
                """
                INSERT INTO departments (
                    organization_id,
                    name,
                    is_active,
                    is_archived
                )
                VALUES (?, ?, TRUE, FALSE)
                RETURNING department_id
                """,
                UUID.class,
                organization.organizationId(),
                "Engineering"
        );

        entityManager.clear();

        UpdateUserRequest request = new UpdateUserRequest(
                departmentId,
                null,
                HUMAN_ACCOUNT_TYPE_ID,
                "EMP-250",
                "Laura",
                "Employee"
        );

        UserResponse updated = userService.updateUser(
                employee.userId(), request
        );

        assertEquals(departmentId, updated.departmentId());
        assertEquals(employee.userId(), updated.userId());
        assertEquals(employee.organizationId(), updated.organizationId());
    }

    @Test
    @Transactional
    void rejectsInactiveAccountTypeDuringUpdate() {

        organizationService.createOrganization(
                new CreateOrganizationRequest(
                        "EPMS Test Organization",
                        "Inactive account type update test"
                )
        );

        UserResponse employee = userService.createUser(
                new CreateUserRequest(
                        null, null, HUMAN_ACCOUNT_TYPE_ID,
                        "EMP-260", "Alice", "Employee",
                        "alice.account@example.com"
                )
        );

        jdbcTemplate.update(
                """
                UPDATE account_types
                SET is_active = FALSE
                WHERE account_type_id = ?
                """,
                HUMAN_ACCOUNT_TYPE_ID
        );

        entityManager.clear();

        UpdateUserRequest request = new UpdateUserRequest(
                null,
                null,
                HUMAN_ACCOUNT_TYPE_ID,
                "EMP-260",
                "Alice",
                "Updated"
        );

        ResourceNotFoundException exception = assertThrows(
                ResourceNotFoundException.class,
                () -> userService.updateUser(employee.userId(), request)
        );

        assertEquals(
                "The requested account type is unavailable.",
                exception.getMessage()
        );
    }

    @Test
    @Transactional
    void rejectsUserRetrievalBeforeOrganizationSetup() {

        UUID userId = UUID.randomUUID();

        ResourceNotFoundException exception = assertThrows(
                ResourceNotFoundException.class,
                () -> userService.getUser(userId)
        );

        assertEquals(
                "The organization has not been configured.",
                exception.getMessage()
        );
    }


}
