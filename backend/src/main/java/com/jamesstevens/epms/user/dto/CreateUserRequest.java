
package com.jamesstevens.epms.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request DTO containing the client-supplied information
 * required to create a user.
 *
 * <p>The backend determines the organization, generates the user
 * identifier, and manages lifecycle and audit information.</p>
 *
 * <p>Department and supervisor relationships are optional.
 * Their validity and organizational membership will be verified
 * by the service layer.</p>
 */
public record CreateUserRequest(

        UUID departmentId,

        UUID supervisorUserId,

        @NotNull
        UUID accountTypeId,

        @Size(max = 50)
        String employeeNumber,

        @NotBlank
        @Size(max = 100)
        String firstName,

        @NotBlank
        @Size(max = 100)
        String lastName,

        @NotBlank
        @Email
        @Size(max = 320)
        String email

) {
}
