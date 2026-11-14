
package com.jamesstevens.epms.common.exception;

/**
 * Indicates that an application operation conflicts with
 * an existing resource or business constraint.
 *
 * <p>The global exception handler will translate this exception
 * into an HTTP 409 Conflict response.</p>
 */
public class ResourceConflictException extends RuntimeException {

    /**
     * Creates an exception with a descriptive conflict message.
     *
     * @param message description of the resource conflict
     */
    public ResourceConflictException(String message) {
        super(message);
    }
}
