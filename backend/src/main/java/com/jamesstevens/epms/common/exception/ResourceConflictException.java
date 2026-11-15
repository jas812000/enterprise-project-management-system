package com.jamesstevens.epms.common.exception;

import java.io.Serial;

/**
 * Indicates that an application operation conflicts with
 * an existing resource or business constraint.
 *
 * <p>The global exception handler will translate this exception
 * into an HTTP 409 Conflict response.</p>
 */
public class ResourceConflictException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with a descriptive conflict message.
     *
     * @param message description of the resource conflict
     */
    public ResourceConflictException(String message) {
        super(message);
    }
}
