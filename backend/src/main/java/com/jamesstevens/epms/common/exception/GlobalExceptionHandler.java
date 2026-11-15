package com.jamesstevens.epms.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.validation.FieldError;
import org.springframework.http.HttpStatusCode;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Provides centralized exception handling for EPMS REST APIs.
 *
 * <p>Converts application exceptions into standardized HTTP
 * ProblemDetail responses.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handles requests for resources that do not exist.
     *
     * @param exception the resource-not-found exception
     * @return an HTTP 404 response with a ProblemDetail body
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleResourceNotFound(
            ResourceNotFoundException exception) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                exception.getMessage()
        );

        problem.setTitle("Resource Not Found");

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    /**
     * Handles operations that conflict with existing resources
     * or application constraints.
     *
     * @param exception the resource-conflict exception
     * @return an HTTP 409 response with a ProblemDetail body
     */
    @ExceptionHandler(ResourceConflictException.class)
    public ResponseEntity<ProblemDetail> handleResourceConflict(
            ResourceConflictException exception) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                exception.getMessage()
        );

        problem.setTitle("Resource Conflict");

        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * Handles lifecycle transitions that violate business rules.
     *
     * @param exception the invalid-state-transition exception
     * @return an HTTP 409 response with a ProblemDetail body
     */
    @ExceptionHandler(InvalidStateTransitionException.class)
    public ResponseEntity<ProblemDetail> handleInvalidStateTransition(
            InvalidStateTransitionException exception) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                exception.getMessage()
        );

        problem.setTitle("Invalid State Transition");

        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }


    /**
     * Handles Bean Validation failures for request objects.
     *
     * <p>Returns HTTP 400 Bad Request with field-specific
     * validation messages in the ProblemDetail response.</p>
     *
     * @param exception the validation exception
     * @param headers HTTP response headers
     * @param status the HTTP status
     * @param request the current web request
     * @return a standardized validation error response
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            @NonNull MethodArgumentNotValidException exception,
            @NonNull HttpHeaders headers,
            @NonNull HttpStatusCode status,
            @NonNull WebRequest request) {

        // Create a standardized HTTP 400 error response.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "One or more request fields failed validation."
        );

        problem.setTitle("Validation Failed");

        // Collect field names and their validation messages.
        Map<String, String> fieldErrors = new LinkedHashMap<>();

        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(
                    fieldError.getField(),
                    fieldError.getDefaultMessage()
            );
        }

        // Add the field errors as a ProblemDetail extension.
        problem.setProperty("fieldErrors", fieldErrors);

        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * Handles unexpected application errors.
     *
     * <p>Logs the underlying exception for troubleshooting while
     * preventing sensitive implementation details from being
     * exposed to API clients.</p>
     *
     * @param exception the unexpected exception
     * @return an HTTP 500 response with a safe error message
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpectedException(
            Exception exception) {

        // Record the exception and stack trace in application logs.
        LOGGER.error("An unexpected application error occurred", exception);

        // Return a generic message without exposing internal details.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Please try again later."
        );

        problem.setTitle("Internal Server Error");

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(problem);
    }
}
