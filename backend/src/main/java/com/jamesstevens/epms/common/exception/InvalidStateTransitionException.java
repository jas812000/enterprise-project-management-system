package com.jamesstevens.epms.common.exception;

import java.io.Serial;

/**
 * Indicates that a requested lifecycle state transition
 * violates an application's business rules.
 *
 * <p>The global exception handler will translate this exception
 * into an HTTP 409 Conflict response.</p>
 */
public class InvalidStateTransitionException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception describing an invalid state transition.
     *
     * @param message description of the invalid transition
     */
    public InvalidStateTransitionException(String message) {
        super(message);
    }
}
