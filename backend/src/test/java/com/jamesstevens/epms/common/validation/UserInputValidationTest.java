
package com.jamesstevens.epms.common.validation;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserInputValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        validatorFactory.close();
    }

    // Sample request DTO used to test Jakarta Bean Validation independently
    // of application entities, services, controllers, and the database.
    record SampleUserRequest(
            @NotBlank String firstName,
            @NotBlank String lastName,
            @NotBlank @Email String email
    ) {}

    // Sample DTO used to verify required non-string values and string length limits.
    // These constraints are tested independently of the existing user input tests.
    record SampleValidationRequest(
            @NotNull Integer sortOrder,
            @Size(min = 2, max = 100) String displayName
    ) {}

    // Verifies that a request containing valid names and a properly formatted
    // email address passes validation without producing any violations.
    @Test
    void validInputShouldHaveNoViolations() {
        var request = new SampleUserRequest(
                "Jane",
                "Smith",
                "jane@example.com"
        );

        var violations = validator.validate(request);

        assertTrue(violations.isEmpty());
    }

    // Verifies that a first name containing only whitespace is rejected
    // by @NotBlank and that the violation identifies the firstName field.
    @Test
    void blankFirstNameShouldBeRejected() {
        var request = new SampleUserRequest(
                " ",
                "Smith",
                "jane@example.com"
        );

        var violations = validator.validate(request);

        assertEquals(1, violations.size());
        assertEquals(
                "firstName",
                violations.iterator().next()
                        .getPropertyPath().toString()
        );
    }

    // Verifies that an empty last name is rejected by @NotBlank
    // and that the violation identifies the lastName field.
    @Test
    void blankLastNameShouldBeRejected() {
        var request = new SampleUserRequest(
                "Jane",
                "",
                "jane@example.com"
        );

        var violations = validator.validate(request);

        assertEquals(1, violations.size());
        assertEquals(
                "lastName",
                violations.iterator().next()
                        .getPropertyPath().toString()
        );
    }

    // Verifies that an incorrectly formatted email address is rejected
    // by @Email and that the violation identifies the email field.
    @Test
    void invalidEmailShouldBeRejected() {
        var request = new SampleUserRequest(
                "Jane",
                "Smith",
                "invalid-email"
        );

        var violations = validator.validate(request);

        assertEquals(1, violations.size());
        assertEquals(
                "email",
                violations.iterator().next()
                        .getPropertyPath().toString()
        );
    }

    // Verifies that an email containing only whitespace is rejected
    // and that at least one validation violation identifies the email field.
    // Both @NotBlank and @Email are applied to this field.
    @Test
    void blankEmailShouldBeRejected() {
        var request = new SampleUserRequest(
                "Jane",
                "Smith",
                " "
        );

        var violations = validator.validate(request);

        assertTrue(
                violations.stream().anyMatch(
                        violation -> violation.getPropertyPath()
                                .toString().equals("email")
                )
        );
    }


    // Verifies that a null Integer is rejected by @NotNull
    // and that the violation identifies the sortOrder field.
    @Test
    void nullSortOrderShouldBeRejected() {
        var request = new SampleValidationRequest(
                null,
                "Engineering"
        );

        var violations = validator.validate(request);

        assertEquals(1, violations.size());
        assertEquals(
                "sortOrder",
                violations.iterator().next()
                        .getPropertyPath().toString()
        );
    }

    // Verifies that a non-null Integer satisfies @NotNull.
    // The value zero is valid because @NotNull checks only for null.
    @Test
    void zeroSortOrderShouldBeAccepted() {
        var request = new SampleValidationRequest(
                0,
                "Engineering"
        );

        var violations = validator.validate(request);

        assertTrue(violations.isEmpty());
    }

    // Verifies that a display name shorter than the minimum
    // length of two characters is rejected by @Size.
    @Test
    void shortDisplayNameShouldBeRejected() {
        var request = new SampleValidationRequest(
                10,
                "A"
        );

        var violations = validator.validate(request);

        assertEquals(1, violations.size());
        assertEquals(
                "displayName",
                violations.iterator().next()
                        .getPropertyPath().toString()
        );
    }

    // Verifies that a display name exceeding the maximum
    // length of 100 characters is rejected by @Size.
    @Test
    void longDisplayNameShouldBeRejected() {
        var request = new SampleValidationRequest(
                10,
                "A".repeat(101)
        );

        var violations = validator.validate(request);

        assertEquals(1, violations.size());
        assertEquals(
                "displayName",
                violations.iterator().next()
                        .getPropertyPath().toString()
        );
    }

    // Verifies that a display name at the maximum permitted
    // length of 100 characters passes @Size validation.
    @Test
    void maximumLengthDisplayNameShouldBeAccepted() {
        var request = new SampleValidationRequest(
                10,
                "A".repeat(100)
        );

        var violations = validator.validate(request);

        assertTrue(violations.isEmpty());
    }

}
