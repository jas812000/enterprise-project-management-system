package com.jamesstevens.epms.common.exception;

import com.jamesstevens.epms.security.web.AuthenticatedSessionValidationFilter;
import com.jamesstevens.epms.security.web.AuthenticationRateLimitFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies centralized exception handling through Spring MVC.
 *
 * <p>Uses a test-only controller and disables security filters
 * to isolate exception-handling behavior.</p>
 */
@WebMvcTest(
        controllers = GlobalExceptionHandlerMvcTest.TestController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {AuthenticatedSessionValidationFilter.class, AuthenticationRateLimitFilter.class}
        )
)
@Import({
        GlobalExceptionHandler.class,
        GlobalExceptionHandlerMvcTest.TestController.class
})
@AutoConfigureMockMvc(addFilters = false)
class GlobalExceptionHandlerMvcTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * Verifies that a missing resource produces HTTP 404
     * with a standardized ProblemDetail JSON response.
     */
    @Test
    void resourceNotFoundShouldReturn404() throws Exception {

        // Act: Send an HTTP GET request to the test endpoint.
        mockMvc.perform(get("/test/not-found"))

                // Assert: Verify the HTTP status.
                .andExpect(status().isNotFound())

                // Assert: Verify the response content type.
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON
                ))

                // Assert: Verify the ProblemDetail properties.
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.detail").value("User not found"));
    }

    /**
     * Verifies that a resource conflict produces HTTP 409
     * with a standardized ProblemDetail JSON response.
     */
    @Test
    void resourceConflictShouldReturn409() throws Exception {

        // Act: Send an HTTP GET request that triggers a resource conflict.
        mockMvc.perform(get("/test/conflict"))

                // Assert: Verify the HTTP status.
                .andExpect(status().isConflict())

                // Assert: Verify the response content type.
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON
                ))

                // Assert: Verify the ProblemDetail properties.
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Resource Conflict"))
                .andExpect(jsonPath("$.detail")
                        .value("Department name already exists"));
    }

    /**
     * Verifies that an invalid lifecycle transition produces HTTP 409
     * with a standardized ProblemDetail JSON response.
     */
    @Test
    void invalidStateTransitionShouldReturn409() throws Exception {

        // Act: Send an HTTP GET request that triggers an invalid transition.
        mockMvc.perform(get("/test/invalid-transition"))

                // Assert: Verify the HTTP status.
                .andExpect(status().isConflict())

                // Assert: Verify the response content type.
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON
                ))

                // Assert: Verify the ProblemDetail properties.
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Invalid State Transition"))
                .andExpect(jsonPath("$.detail")
                        .value("Cannot transition project from Planned to Completed"));
    }

    /**
     * Verifies that invalid request data produces HTTP 400
     * with field-specific validation errors.
     */
    @Test
    void invalidRequestShouldReturn400WithFieldErrors() throws Exception {

        // Arrange: Create a JSON request with a blank department name.
        String requestBody = """
            {
                "name": "   "
            }
            """;

        // Act: Submit the invalid request to the test endpoint.
        mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))

                // Assert: Verify the HTTP status.
                .andExpect(status().isBadRequest())

                // Assert: Verify the ProblemDetail content type.
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON
                ))

                // Assert: Verify the standardized error response.
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.detail")
                        .value("One or more request fields failed validation."))

                // Assert: Verify the field-specific validation message.
                .andExpect(jsonPath("$.fieldErrors.name")
                        .value("Department name is required"));
    }

    /**
     * Verifies that unexpected exceptions produce HTTP 500
     * without exposing internal exception details.
     */
    @Test
    void unexpectedExceptionShouldReturn500WithoutInternalDetails() throws Exception {

        // Act: Send an HTTP request that triggers an unexpected exception.
        mockMvc.perform(get("/test/unexpected"))

                // Assert: Verify the HTTP status.
                .andExpect(status().isInternalServerError())

                // Assert: Verify the standardized response content type.
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON
                ))

                // Assert: Verify the generic ProblemDetail response.
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.title").value("Internal Server Error"))
                .andExpect(jsonPath("$.detail").value(
                        "An unexpected error occurred. Please try again later."
                ))

                // Assert: Verify that the internal exception message is not exposed.
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString(
                                        "Internal database connection details"
                                )
                        )
                ));
    }

    /**
     * Verifies that unsupported HTTP methods retain Spring MVC's
     * standard HTTP 405 response instead of becoming HTTP 500.
     */
    @Test
    void unsupportedHttpMethodShouldReturn405() throws Exception {

        // Act: Send POST to an endpoint that only supports GET.
        mockMvc.perform(post("/test/not-found"))

                // Assert: Verify Spring MVC returns Method Not Allowed.
                .andExpect(status().isMethodNotAllowed())

                // Assert: Verify the standardized error content type.
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON
                ))

                // Assert: Verify the ProblemDetail status.
                .andExpect(jsonPath("$.status").value(405));
    }

    /**
     * Represents a test-only request used to verify Bean Validation.
     *
     * @param name department name, which must not be blank
     */
    public record TestDepartmentRequest(
            @NotBlank(message = "Department name is required")
            String name
    ) {
    }

    /**
     * Provides test endpoints without adding production controllers.
     */
    @RestController
    public static class TestController {

        /**
         * Simulates a service failing to find a requested resource.
         */
        @GetMapping("/test/not-found")
        public void resourceNotFound() {
            throw new ResourceNotFoundException("User not found");
        }

        /**
         * Simulates a request that conflicts with existing application data.
         */
        @GetMapping("/test/conflict")
        public void resourceConflict() {
            throw new ResourceConflictException("Department name already exists");
        }

        /**
         * Simulates an invalid project lifecycle transition.
         */
        @GetMapping("/test/invalid-transition")
        public void invalidStateTransition() {
            throw new InvalidStateTransitionException(
                    "Cannot transition project from Planned to Completed"
            );
        }

        /**
         * Simulates a request that requires Bean Validation.
         *
         * @param request the department data submitted by the client
         */
        @PostMapping("/test/validation")
        public void validateDepartment(
                @Valid @RequestBody TestDepartmentRequest request
        ) {
            // No implementation required.
            // Spring validates the request before invoking this method.
        }

        /**
         * Simulates an unexpected application error.
         */
        @GetMapping("/test/unexpected")
        public void unexpectedError() {
            throw new IllegalStateException(
                    "Internal database connection details"
            );
        }

    }
}
