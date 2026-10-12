
package com.jamesstevens.epms.organization.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO used to create the organization's initial record.
 * <p>
 * Each EPMS installation supports only one organization.
 * The organization ID, audit fields, active status, timestamps,
 * and version are managed by the backend.
 * <p>
 * @param name Required organization name, maximum 200 characters.
 * @param description Optional organization description.
 */
public record CreateOrganizationRequest(

        @NotBlank
        @Size(max = 200)
        String name,

        String description

) {}
