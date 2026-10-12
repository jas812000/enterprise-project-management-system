
package com.jamesstevens.epms.user.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Response DTO containing user information returned to API clients.
 *
 * <p>This record exposes user identity, organizational relationships,
 * account classification, contact information, lifecycle status,
 * timestamps, and optimistic-locking version information.</p>
 *
 * <p>Internal audit identifiers, pending email changes, archival
 * details, and review metadata are intentionally excluded.</p>
 *
 * <p>Response values are populated by the backend. Clients cannot
 * directly modify these properties through this DTO.</p>
 */
public record UserResponse(

        UUID userId,

        UUID organizationId,

        UUID departmentId,

        UUID supervisorUserId,

        UUID accountTypeId,

        String employeeNumber,

        String firstName,

        String lastName,

        String email,

        boolean isActive,

        boolean isArchived,

        OffsetDateTime createdAt,

        OffsetDateTime updatedAt,

        long version

) {
}
