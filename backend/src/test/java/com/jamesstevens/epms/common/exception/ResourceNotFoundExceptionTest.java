
package com.jamesstevens.epms.common.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ResourceNotFoundExceptionTest {

    /**
     * Verifies that the exception preserves the message
     * provided when it is constructed.
     */
    @Test
    void constructorShouldPreserveMessage() {
        // Arrange: Define a message describing a missing resource.
        String message = "User not found";

        // Act: Create the custom exception.
        ResourceNotFoundException exception =
                new ResourceNotFoundException(message);

        // Assert: Verify the exception retains the message.
        assertEquals(message, exception.getMessage());
    }

    /**
     * Verifies that the exception is an unchecked exception.
     */
    @Test
    void exceptionShouldExtendRuntimeException() {
        // Arrange and Act: Create the custom exception.
        ResourceNotFoundException exception =
                new ResourceNotFoundException("User not found");

        // Assert: Verify its exception type.
        assertInstanceOf(RuntimeException.class, exception);
    }
}
