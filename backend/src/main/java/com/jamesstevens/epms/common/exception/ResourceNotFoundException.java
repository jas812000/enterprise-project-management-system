package com.jamesstevens.epms.common.exception;

import java.io.Serial;

/**
 * Indicates that a requested application resource could not be found.
 *
 * <p>This exception is intended for service-layer operations when
 * a requested resource does not exist. The global exception handler
 * will translate it into an HTTP 404 Not Found response.</p>
 */
public class ResourceNotFoundException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with a descriptive error message.
     *
     * @param message description of the resource that was not found
     */
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
