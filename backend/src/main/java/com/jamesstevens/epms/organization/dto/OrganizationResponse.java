
package com.jamesstevens.epms.organization.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Response DTO containing organization information returned to API clients.
 *
 * This DTO exposes organization identity, descriptive information,
 * active status, timestamps, and optimistic locking version.
 *
 * Database entities are not exposed directly through the API.
 *
 * @param organizationId Unique organization identifier.
 * @param name Organization name.
 * @param description Optional organization description.
 * @param isActive Indicates whether the organization is active.
 * @param createdAt Timestamp when the organization was created.
 * @param updatedAt Timestamp when the organization was last updated.
 * @param version Optimistic locking version.
 */
public record OrganizationResponse(

        UUID organizationId,

        String name,

        String description,

        boolean isActive,

        OffsetDateTime createdAt,

        OffsetDateTime updatedAt,

        long version

) {}
