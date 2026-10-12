
package com.jamesstevens.epms.organization.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrganizationDtoValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        // Initializes Jakarta Bean Validation before the tests run.
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        // Releases resources associated with the validator factory.
        validatorFactory.close();
    }

    // ---------------------------------------------------------
    // CreateOrganizationRequest validation tests
    // ---------------------------------------------------------

    @Test
    void validCreateOrganizationRequestShouldBeAccepted() {
        // Verifies that a valid organization name and description
        // produce no validation violations.
        var request = new CreateOrganizationRequest(
                "Example Organization",
                "Organization description"
        );

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void blankCreateOrganizationNameShouldBeRejected() {
        // Verifies that whitespace-only organization names are rejected.
        var request = new CreateOrganizationRequest("   ", null);

        assertEquals(1, validator.validate(request).size());
    }

    @Test
    void nullCreateOrganizationNameShouldBeRejected() {
        // Verifies that a null organization name is rejected.
        var request = new CreateOrganizationRequest(null, null);

        assertEquals(1, validator.validate(request).size());
    }

    @Test
    void createOrganizationNameExceeding200CharactersShouldBeRejected() {
        // Verifies that names exceeding the database's
        // VARCHAR(200) limit are rejected.
        var request = new CreateOrganizationRequest(
                "A".repeat(201),
                null
        );

        assertEquals(1, validator.validate(request).size());
    }

    @Test
    void createOrganizationNameWith200CharactersShouldBeAccepted() {
        // Verifies that a name exactly at the maximum
        // permitted length is accepted.
        var request = new CreateOrganizationRequest(
                "A".repeat(200),
                null
        );

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void nullCreateOrganizationDescriptionShouldBeAccepted() {
        // Verifies that description is optional during creation.
        var request = new CreateOrganizationRequest(
                "Example Organization",
                null
        );

        assertTrue(validator.validate(request).isEmpty());
    }

    // ---------------------------------------------------------
    // UpdateOrganizationRequest validation tests
    // ---------------------------------------------------------

    @Test
    void validUpdateOrganizationRequestShouldBeAccepted() {
        // Verifies that valid updated organization details
        // produce no validation violations.
        var request = new UpdateOrganizationRequest(
                "Updated Organization",
                "Updated description"
        );

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void blankUpdateOrganizationNameShouldBeRejected() {
        // Verifies that whitespace-only names are rejected
        // when updating organization details.
        var request = new UpdateOrganizationRequest("   ", null);

        assertEquals(1, validator.validate(request).size());
    }

    @Test
    void nullUpdateOrganizationNameShouldBeRejected() {
        // Verifies that organization names cannot be null
        // during a full update.
        var request = new UpdateOrganizationRequest(null, null);

        assertEquals(1, validator.validate(request).size());
    }

    @Test
    void updateOrganizationNameExceeding200CharactersShouldBeRejected() {
        // Verifies that updated names cannot exceed
        // the database's VARCHAR(200) limit.
        var request = new UpdateOrganizationRequest(
                "A".repeat(201),
                null
        );

        assertEquals(1, validator.validate(request).size());
    }

    @Test
    void nullUpdateOrganizationDescriptionShouldBeAccepted() {
        // Verifies that description remains optional
        // when updating an organization.
        var request = new UpdateOrganizationRequest(
                "Updated Organization",
                null
        );

        assertTrue(validator.validate(request).isEmpty());
    }
}
