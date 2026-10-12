
package com.jamesstevens.epms.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request DTO containing the editable information
 * permitted during a full user update.
 *
 * <p>Email changes are excluded and will be handled
 * through a dedicated verification workflow.</p>
 *
 * <p>The service layer is responsible for validating
 * organizational relationships, account-type assignment
 * permissions, and other business rules.</p>
 *
 * <p>Identity, lifecycle status, and audit information
 * are managed separately by the backend.</p>
 */
public record UpdateUserRequest(

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
        String lastName

) {
}
