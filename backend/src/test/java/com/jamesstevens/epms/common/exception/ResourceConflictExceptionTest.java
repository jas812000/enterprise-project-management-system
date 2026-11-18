package com.jamesstevens.epms.common.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class ResourceConflictExceptionTest {

  /**
   * Verifies that the exception preserves the conflict
   * message provided to its constructor.
   */
  @Test
  void constructorShouldPreserveMessage() {
    // Arrange: Define a message describing the conflict.
    String message = "Department already exists";

    // Act: Create the custom exception.
    ResourceConflictException exception =
            new ResourceConflictException(message);

    // Assert: Verify the message is preserved.
    assertEquals(message, exception.getMessage());
  }

  /**
   * Verifies that the exception preserves both its descriptive
   * message and the original cause.
   */
  @Test
  void constructorShouldPreserveMessageAndCause() {

    // Arrange: Define the conflict and its underlying cause.
    String message = "Organization already exists";
    IllegalStateException cause =
            new IllegalStateException("Original failure");

    // Act: Create the conflict exception with its cause.
    ResourceConflictException exception =
            new ResourceConflictException(message, cause);

    // Assert: Verify both values are preserved.
    assertEquals(message, exception.getMessage());
    assertSame(cause, exception.getCause());
  }

  /**
   * Verifies that the exception is an unchecked exception.
   */
  @Test
  void exceptionShouldExtendRuntimeException() {
    // Arrange and Act: Create the custom exception.
    ResourceConflictException exception =
            new ResourceConflictException("Department already exists");

    // Assert: Verify its exception type.
    assertInstanceOf(RuntimeException.class, exception);
  }
}
