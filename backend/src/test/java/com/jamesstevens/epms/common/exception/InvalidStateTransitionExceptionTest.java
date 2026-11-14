package com.jamesstevens.epms.common.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class InvalidStateTransitionExceptionTest {

    /**
     * Verifies that the exception preserves the message
     * describing the invalid lifecycle transition.
     */
    @Test
    void constructorShouldPreserveMessage() {
        // Arrange: Define an invalid task state transition.
        String message =
                "Task cannot transition directly from Returned to In Review";

        // Act: Create the custom exception.
        InvalidStateTransitionException exception =
                new InvalidStateTransitionException(message);

        // Assert: Verify the exception preserves the message.
        assertEquals(message, exception.getMessage());
    }

    /**
     * Verifies that the exception is an unchecked exception.
     */
    @Test
    void exceptionShouldExtendRuntimeException() {
        // Arrange and Act: Create the custom exception.
        InvalidStateTransitionException exception =
                new InvalidStateTransitionException("Invalid task state transition");

        // Assert: Verify that it extends RuntimeException.
        assertInstanceOf(RuntimeException.class, exception);
    }
}
