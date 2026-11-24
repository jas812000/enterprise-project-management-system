package com.jamesstevens.epms.security.rbac;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.UncategorizedSQLException;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;

class RbacAdministrationExceptionHandlerTest {
    private final RbacAdministrationExceptionHandler handler = new RbacAdministrationExceptionHandler();

    @Test void invalidRequestIsBadRequest() {
        var response = handler.invalid(new IllegalArgumentException("private detail"));
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertFalse(response.getBody().getDetail().contains("private"));
    }

    @Test void deniedActorIsForbidden() {
        var response = handler.forbidden(new org.springframework.security.access.AccessDeniedException("private"));
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test void databaseBusinessRejectionIsConflictWithoutSqlLeak() {
        var error = new UncategorizedSQLException("rbac", "SELECT secret", new SQLException("private sql", "P0001"));
        var response = handler.database(error);
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertFalse(response.getBody().getDetail().contains("private"));
    }

    @Test void databaseOutageIsServiceUnavailable() {
        var response = handler.database(new DataAccessResourceFailureException("private db host"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    }
}
