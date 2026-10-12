
package com.jamesstevens.epms.user.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserDtoValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        // Initializes Jakarta Bean Validation before executing the tests.
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        // Releases resources associated with the validator factory.
        validatorFactory.close();
    }

    // Creates a valid request that individual tests can modify.
    private static CreateUserRequest createRequest(
            UUID accountTypeId,
            String employeeNumber,
            String firstName,
            String lastName,
            String email
    ) {
        return new CreateUserRequest(
                null,
                null,
                accountTypeId,
                employeeNumber,
                firstName,
                lastName,
                email
        );
    }

    // Creates a valid update request that individual tests can modify.
    private static UpdateUserRequest updateRequest(
            UUID accountTypeId,
            String employeeNumber,
            String firstName,
            String lastName
    ) {
        return new UpdateUserRequest(
                null,
                null,
                accountTypeId,
                employeeNumber,
                firstName,
                lastName
        );
    }

    // Verifies that exactly one constraint violation belongs to the
    // expected field, without depending on provider-specific messages.
    private static <T> void assertSingleViolation(
            T request,
            String fieldName
    ) {
        Set<ConstraintViolation<T>> violations = validator.validate(request);

        assertEquals(1, violations.size());
        assertEquals(
                fieldName,
                violations.iterator().next().getPropertyPath().toString()
        );
    }

    // ---------------------------------------------------------
    // CreateUserRequest validation tests
    // ---------------------------------------------------------

    @Test
    void validCreateUserRequestShouldBeAccepted() {
        // Verifies that valid required fields pass validation.
        var request = createRequest(
                UUID.randomUUID(),
                "EMP-001",
                "Jane",
                "Smith",
                "jane@example.com"
        );

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void nullCreateAccountTypeIdShouldBeRejected() {
        // Verifies that an account type is required during creation.
        var request = createRequest(
                null, null, "Jane", "Smith", "jane@example.com"
        );

        assertSingleViolation(request, "accountTypeId");
    }

    @Test
    void nullCreateFirstNameShouldBeRejected() {
        // Verifies that the first name cannot be null.
        var request = createRequest(
                UUID.randomUUID(), null, null, "Smith", "jane@example.com"
        );

        assertSingleViolation(request, "firstName");
    }

    @Test
    void blankCreateFirstNameShouldBeRejected() {
        // Verifies that whitespace-only first names are rejected.
        var request = createRequest(
                UUID.randomUUID(), null, "   ", "Smith", "jane@example.com"
        );

        assertSingleViolation(request, "firstName");
    }

    @Test
    void createFirstNameExceeding100CharactersShouldBeRejected() {
        // Verifies that first names cannot exceed VARCHAR(100).
        var request = createRequest(
                UUID.randomUUID(), null, "A".repeat(101),
                "Smith", "jane@example.com"
        );

        assertSingleViolation(request, "firstName");
    }

    @Test
    void nullCreateLastNameShouldBeRejected() {
        // Verifies that the last name cannot be null.
        var request = createRequest(
                UUID.randomUUID(), null, "Jane", null, "jane@example.com"
        );

        assertSingleViolation(request, "lastName");
    }

    @Test
    void blankCreateLastNameShouldBeRejected() {
        // Verifies that whitespace-only last names are rejected.
        var request = createRequest(
                UUID.randomUUID(), null, "Jane", "   ", "jane@example.com"
        );

        assertSingleViolation(request, "lastName");
    }

    @Test
    void createLastNameExceeding100CharactersShouldBeRejected() {
        // Verifies that last names cannot exceed VARCHAR(100).
        var request = createRequest(
                UUID.randomUUID(), null, "Jane",
                "A".repeat(101), "jane@example.com"
        );

        assertSingleViolation(request, "lastName");
    }

    @Test
    void nullCreateEmailShouldBeRejected() {
        // Verifies that an email address is required.
        var request = createRequest(
                UUID.randomUUID(), null, "Jane", "Smith", null
        );

        assertSingleViolation(request, "email");
    }

    @Test
    void blankCreateEmailShouldBeRejected() {
        // Verifies that whitespace-only email addresses are rejected.
        var request = createRequest(
                UUID.randomUUID(), null, "Jane", "Smith", "   "
        );

        // Both @NotBlank and @Email may report violations,
        // so check the field rather than requiring exactly one.
        var violations = validator.validate(request);

        assertTrue(violations.stream().anyMatch(
                violation -> violation.getPropertyPath()
                        .toString().equals("email")
        ));
    }

    @Test
    void invalidCreateEmailFormatShouldBeRejected() {
        // Verifies that malformed email addresses fail @Email validation.
        var request = createRequest(
                UUID.randomUUID(), null, "Jane", "Smith", "not-an-email"
        );

        assertSingleViolation(request, "email");
    }

    @Test
    void createEmailExceeding320CharactersShouldBeRejected() {
        // Verifies that email addresses exceeding VARCHAR(320)
        // fail the maximum-length constraint.
        var request = createRequest(
                UUID.randomUUID(), null, "Jane", "Smith",
                "a".repeat(310) + "@example.com"
        );

        var violations = validator.validate(request);

        assertTrue(violations.stream().anyMatch(
                violation -> violation.getPropertyPath()
                        .toString().equals("email")
                        && violation.getConstraintDescriptor()
                        .getAnnotation().annotationType()
                        .getSimpleName().equals("Size")
        ));
    }

    @Test
    void createEmployeeNumberExceeding50CharactersShouldBeRejected() {
        // Verifies that employee numbers cannot exceed VARCHAR(50).
        var request = createRequest(
                UUID.randomUUID(), "A".repeat(51),
                "Jane", "Smith", "jane@example.com"
        );

        assertSingleViolation(request, "employeeNumber");
    }

    // ---------------------------------------------------------
    // UpdateUserRequest validation tests
    // ---------------------------------------------------------

    @Test
    void validUpdateUserRequestShouldBeAccepted() {
        // Verifies that valid editable fields pass validation.
        var request = updateRequest(
                UUID.randomUUID(), "EMP-002", "Janet", "Smith"
        );

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void nullUpdateAccountTypeIdShouldBeRejected() {
        // Verifies that account type remains required for full updates.
        var request = updateRequest(
                null, null, "Jane", "Smith"
        );

        assertSingleViolation(request, "accountTypeId");
    }

    @Test
    void nullUpdateFirstNameShouldBeRejected() {
        // Verifies that first name cannot be null during an update.
        var request = updateRequest(
                UUID.randomUUID(), null, null, "Smith"
        );

        assertSingleViolation(request, "firstName");
    }

    @Test
    void blankUpdateFirstNameShouldBeRejected() {
        // Verifies that whitespace-only first names are rejected.
        var request = updateRequest(
                UUID.randomUUID(), null, "   ", "Smith"
        );

        assertSingleViolation(request, "firstName");
    }

    @Test
    void updateFirstNameExceeding100CharactersShouldBeRejected() {
        // Verifies that updated first names cannot exceed 100 characters.
        var request = updateRequest(
                UUID.randomUUID(), null, "A".repeat(101), "Smith"
        );

        assertSingleViolation(request, "firstName");
    }

    @Test
    void nullUpdateLastNameShouldBeRejected() {
        // Verifies that last name cannot be null during an update.
        var request = updateRequest(
                UUID.randomUUID(), null, "Jane", null
        );

        assertSingleViolation(request, "lastName");
    }

    @Test
    void blankUpdateLastNameShouldBeRejected() {
        // Verifies that whitespace-only last names are rejected.
        var request = updateRequest(
                UUID.randomUUID(), null, "Jane", "   "
        );

        assertSingleViolation(request, "lastName");
    }

    @Test
    void updateLastNameExceeding100CharactersShouldBeRejected() {
        // Verifies that updated last names cannot exceed 100 characters.
        var request = updateRequest(
                UUID.randomUUID(), null, "Jane", "A".repeat(101)
        );

        assertSingleViolation(request, "lastName");
    }

    @Test
    void updateEmployeeNumberExceeding50CharactersShouldBeRejected() {
        // Verifies that updated employee numbers cannot exceed 50 characters.
        var request = updateRequest(
                UUID.randomUUID(), "A".repeat(51), "Jane", "Smith"
        );

        assertSingleViolation(request, "employeeNumber");
    }

    // ---------------------------------------------------------
    // Boundary and optional-field validation tests
    // ---------------------------------------------------------

    @Test
    void createNamesWith100CharactersShouldBeAccepted() {
        // Verifies that both names are accepted at the exact
        // VARCHAR(100) boundary.
        var request = createRequest(
                UUID.randomUUID(), null,
                "A".repeat(100),
                "B".repeat(100),
                "jane@example.com"
        );

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void createEmailWith320CharactersShouldBeAccepted() {
        // Verifies the exact @Size(max = 320) boundary using
        // a valid email with short domain labels.
        String localPart = "a".repeat(64);
        String domain = "b".repeat(63) + "."
                + "c".repeat(63) + "."
                + "d".repeat(63) + "."
                + "e".repeat(63);

        String email = localPart + "@" + domain;

        assertEquals(320, email.length());

        var request = createRequest(
                UUID.randomUUID(), null, "Jane", "Smith", email
        );

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void createEmployeeNumberWith50CharactersShouldBeAccepted() {
        // Verifies that employee numbers exactly 50 characters long
        // are accepted.
        var request = createRequest(
                UUID.randomUUID(), "A".repeat(50),
                "Jane", "Smith", "jane@example.com"
        );

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void nullCreateDepartmentAndSupervisorShouldBeAccepted() {
        // Verifies that department and supervisor relationships
        // are optional during user creation.
        var request = new CreateUserRequest(
                null,
                null,
                UUID.randomUUID(),
                null,
                "Jane",
                "Smith",
                "jane@example.com"
        );

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void nullUpdateDepartmentAndSupervisorShouldBeAccepted() {
        // Verifies that department and supervisor relationships
        // remain optional during full user updates.
        var request = new UpdateUserRequest(
                null,
                null,
                UUID.randomUUID(),
                null,
                "Jane",
                "Smith"
        );

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void nullUpdateEmployeeNumberShouldBeAccepted() {
        // Verifies that an employee number is optional
        // when updating a user.
        var request = updateRequest(
                UUID.randomUUID(), null, "Jane", "Smith"
        );

        assertTrue(validator.validate(request).isEmpty());
    }
}
