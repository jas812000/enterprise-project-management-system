package com.jamesstevens.epms.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.core.MethodParameter;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler =
            new GlobalExceptionHandler();

    /**
     * Verifies that a missing resource produces an HTTP 404 response.
     */
    @Test
    void resourceNotFoundShouldReturn404() {
        // Arrange: Create an exception describing a missing user.
        ResourceNotFoundException exception =
                new ResourceNotFoundException("User not found");

        // Act: Invoke the global exception handler.
        ResponseEntity<ProblemDetail> response =
                handler.handleResourceNotFound(exception);

        // Assert: Verify the HTTP response and ProblemDetail fields.
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());

        ProblemDetail problem = response.getBody();
        assertNotNull(problem);
        assertEquals(404, problem.getStatus());
        assertEquals("Resource Not Found", problem.getTitle());
        assertEquals("User not found", problem.getDetail());
    }

    /**
     * Verifies that a resource conflict produces an HTTP 409 response.
     */
    @Test
    void resourceConflictShouldReturn409() {
        // Arrange: Create an exception describing a duplicate resource.
        ResourceConflictException exception =
                new ResourceConflictException("Department already exists");

        // Act: Invoke the global exception handler.
        ResponseEntity<ProblemDetail> response =
                handler.handleResourceConflict(exception);

        // Assert: Verify the HTTP response and ProblemDetail fields.
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());

        ProblemDetail problem = response.getBody();
        assertNotNull(problem);
        assertEquals(409, problem.getStatus());
        assertEquals("Resource Conflict", problem.getTitle());
        assertEquals("Department already exists", problem.getDetail());
    }

    /**
     * Verifies that an invalid lifecycle transition produces HTTP 409.
     */
    @Test
    void invalidStateTransitionShouldReturn409() {
        // Arrange: Create an exception describing a prohibited transition.
        InvalidStateTransitionException exception =
                new InvalidStateTransitionException(
                        "Task cannot transition directly from Returned to In Review"
                );

        // Act: Invoke the global exception handler.
        ResponseEntity<ProblemDetail> response =
                handler.handleInvalidStateTransition(exception);

        // Assert: Verify the HTTP response and ProblemDetail fields.
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());

        ProblemDetail problem = response.getBody();
        assertNotNull(problem);
        assertEquals(409, problem.getStatus());
        assertEquals("Invalid State Transition", problem.getTitle());
        assertEquals(
                "Task cannot transition directly from Returned to In Review",
                problem.getDetail()
        );
    }

    /**
     * Verifies that unexpected exceptions return HTTP 500
     * without exposing sensitive internal error messages.
     */
    @Test
    void unexpectedExceptionShouldReturn500WithoutExposingDetails() {
        // Arrange: Simulate an unexpected internal application failure.
        Exception exception =
                new RuntimeException("Database connection password: secret123");

        // Act: Invoke the global exception handler.
        ResponseEntity<ProblemDetail> response =
                handler.handleUnexpectedException(exception);

        // Assert: Verify the HTTP status.
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());

        ProblemDetail problem = response.getBody();
        assertNotNull(problem);

        // Assert: Verify the standardized error response.
        assertEquals(500, problem.getStatus());
        assertEquals("Internal Server Error", problem.getTitle());
        assertEquals(
                "An unexpected error occurred. Please try again later.",
                problem.getDetail()
        );

        // Assert: Verify that the original exception message is not exposed.
        assertNotNull(problem.getDetail());
        assertFalse(problem.getDetail().contains("secret123"));
    }

    /**
     * Verifies that Bean Validation failures return HTTP 400
     * with field-specific validation messages.
     */
    @Test
    void validationFailureShouldReturn400WithFieldErrors()
            throws NoSuchMethodException {

        // Arrange: Create an invalid request object.
        Object requestBody = new Object();

        BeanPropertyBindingResult bindingResult =
                new BeanPropertyBindingResult(requestBody, "requestBody");

        // Simulate two field validation failures.
        bindingResult.addError(new FieldError(
                "requestBody",
                "name",
                "must not be blank"
        ));

        bindingResult.addError(new FieldError(
                "requestBody",
                "email",
                "must be a well-formed email address"
        ));

        // Obtain method metadata for the validation exception.
        Method method = TestController.class.getDeclaredMethod(
                "create",
                Object.class
        );

        MethodParameter parameter = new MethodParameter(method, 0);

        MethodArgumentNotValidException exception =
                new MethodArgumentNotValidException(parameter, bindingResult);

        MockHttpServletRequest servletRequest =
                new MockHttpServletRequest();

        ServletWebRequest webRequest =
                new ServletWebRequest(servletRequest);

        // Act: Invoke the validation handler.
        ResponseEntity<Object> response =
                handler.handleMethodArgumentNotValid(
                        exception,
                        new HttpHeaders(),
                        HttpStatus.BAD_REQUEST,
                        webRequest
                );

        // Assert: Verify that the handler returned a response.
        assertNotNull(response);

        // Assert: Verify HTTP 400.
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());

        ProblemDetail problem =
                assertInstanceOf(ProblemDetail.class, response.getBody());

        assertEquals(400, problem.getStatus());
        assertEquals("Validation Failed", problem.getTitle());
        assertEquals(
                "One or more request fields failed validation.",
                problem.getDetail()
        );

        // Assert: Verify that ProblemDetail contains extension properties.
        Map<String, Object> properties = problem.getProperties();
        assertNotNull(properties);

        // Assert: Verify that fieldErrors is a Map.
        Map<?, ?> fieldErrors = assertInstanceOf(
                Map.class,
                properties.get("fieldErrors")
        );

        assertEquals("must not be blank", fieldErrors.get("name"));
        assertEquals(
                "must be a well-formed email address",
                fieldErrors.get("email")
        );
    }

    /**
     * Supplies method metadata for the simulated request.
     * This is test-only scaffolding, not a production controller.
     */
    private static class TestController {

        @SuppressWarnings("unused")
        void create(Object requestBody) {
        }
    }

}
