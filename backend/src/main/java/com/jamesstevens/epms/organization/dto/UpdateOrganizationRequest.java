
package com.jamesstevens.epms.organization.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO used to update an existing organization's details.
 *
 * Only editable organization information is accepted.
 * Organization identity, audit fields, active status, timestamps,
 * and version are managed separately by the backend.
 *
 * @param name Required organization name, maximum 200 characters.
 * @param description Optional organization description.
 */
public record UpdateOrganizationRequest(

        @NotBlank
        @Size(max = 200)
        String name,

        String description

) {}
