package com.jamesstevens.epms.security.rbac;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.sql.SQLException;

/** Sanitizes RBAC-specific errors without exposing database error text. */
@RestControllerAdvice(assignableTypes = RbacAdministrationController.class)
public class RbacAdministrationExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(RbacAdministrationExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> invalid(IllegalArgumentException exception) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid RBAC Request", "The RBAC request is invalid.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> forbidden(AccessDeniedException exception) {
        return problem(HttpStatus.FORBIDDEN, "RBAC Access Denied", "RBAC administration is not permitted.");
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ProblemDetail> database(DataAccessException exception) {
        Throwable cause = exception;
        boolean businessRejection = false;
        while (cause != null) {
            if (cause instanceof SQLException sql && "P0001".equals(sql.getSQLState())) {
                businessRejection = true;
                break;
            }
            cause = cause.getCause();
        }
        ResponseEntity<ProblemDetail> response;
        if (businessRejection) {
            response = problem(HttpStatus.CONFLICT, "RBAC Operation Rejected",
                    "The requested role change was rejected.");
        } else {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error("RBAC database operation failed: {}", exception);
            }
            response = problem(HttpStatus.SERVICE_UNAVAILABLE, "RBAC Temporarily Unavailable",
                    "RBAC administration is temporarily unavailable.");
        }
        return response;
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String title, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setTitle(title);
        return ResponseEntity.status(status).body(body);
    }
}
