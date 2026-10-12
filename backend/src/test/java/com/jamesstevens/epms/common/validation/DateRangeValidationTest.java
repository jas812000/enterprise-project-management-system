
package com.jamesstevens.epms.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DateRangeValidationTest {

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

    // Defines a custom class-level validation constraint.
    // The constraint delegates date comparisons to DateRangeValidator.
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = DateRangeValidator.class)
    @interface ValidDateRange {

        String message() default
                "End date must not be before start date";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    // Sample request DTO used exclusively for testing cross-field validation.
    @ValidDateRange
    public record SampleDateRangeRequest(
            LocalDate startDate,
            LocalDate endDate
    ) {}

    // Implements the custom constraint by comparing both date fields.
    // Null values are allowed here because required-field validation
    // should be handled separately using @NotNull when appropriate.
    public static class DateRangeValidator
            implements ConstraintValidator<
            ValidDateRange, SampleDateRangeRequest> {

        // Public no-argument constructor required for Hibernate Validator
        // to instantiate this constraint validator through reflection.
        public DateRangeValidator() {
        }

        @Override
        public boolean isValid(
                SampleDateRangeRequest request,
                ConstraintValidatorContext context) {

            if (request == null) {
                return true;
            }

            if (request.startDate() == null
                    || request.endDate() == null) {
                return true;
            }

            return !request.endDate()
                    .isBefore(request.startDate());
        }
    }

    // Verifies that an end date occurring after the start date
    // passes the custom date range validation constraint.
    @Test
    void endDateAfterStartDateShouldBeAccepted() {
        var request = new SampleDateRangeRequest(
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 15)
        );

        var violations = validator.validate(request);

        assertTrue(violations.isEmpty());
    }

    // Verifies that identical start and end dates are accepted
    // because the end date is not earlier than the start date.
    @Test
    void matchingStartAndEndDatesShouldBeAccepted() {
        var request = new SampleDateRangeRequest(
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 1)
        );

        var violations = validator.validate(request);

        assertTrue(violations.isEmpty());
    }

    // Verifies that an end date earlier than the start date
    // produces exactly one class-level validation violation.
    @Test
    void endDateBeforeStartDateShouldBeRejected() {
        var request = new SampleDateRangeRequest(
                LocalDate.of(2026, 10, 15),
                LocalDate.of(2026, 10, 1)
        );

        var violations = validator.validate(request);

        assertEquals(1, violations.size());
        assertEquals(
                "End date must not be before start date",
                violations.iterator().next().getMessage()
        );
    }

    // Verifies that a missing end date does not trigger the
    // cross-field constraint, allowing @NotNull to handle it separately.
    @Test
    void nullEndDateShouldBeAcceptedByDateRangeValidator() {
        var request = new SampleDateRangeRequest(
                LocalDate.of(2026, 10, 1),
                null
        );

        var violations = validator.validate(request);

        assertTrue(violations.isEmpty());
    }

    // Verifies that a missing start date does not trigger the
    // cross-field constraint, allowing @NotNull to handle it separately.
    @Test
    void nullStartDateShouldBeAcceptedByDateRangeValidator() {
        var request = new SampleDateRangeRequest(
                null,
                LocalDate.of(2026, 10, 15)
        );

        var violations = validator.validate(request);

        assertTrue(violations.isEmpty());
    }
}
