package com.jamesstevens.epms.organization.service;

import com.jamesstevens.epms.common.exception.ResourceConflictException;
import com.jamesstevens.epms.organization.dto.CreateOrganizationRequest;
import com.jamesstevens.epms.organization.entity.Organization;
import com.jamesstevens.epms.organization.repository.OrganizationRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OrganizationServiceConflictTest {

    private OrganizationRepository organizationRepository;
    private OrganizationService organizationService;

    @BeforeEach
    void setUp() {
        organizationRepository = mock(OrganizationRepository.class);
        organizationService = new OrganizationService(organizationRepository);
    }

    @Test
    void translatesSingleOrganizationConstraintViolation() {

        when(organizationRepository.findFirstBy())
                .thenReturn(Optional.empty());

        SQLException sqlException = new SQLException(
                "Duplicate organization",
                "23505"
        );

        ConstraintViolationException constraintException =
                new ConstraintViolationException(
                        "Single-organization constraint violated",
                        sqlException,
                        "ux_organizations_single_instance"
                );

        DataIntegrityViolationException dataException =
                new DataIntegrityViolationException(
                        "Database constraint violation",
                        constraintException
                );

        when(organizationRepository.saveAndFlush(any(Organization.class)))
                .thenThrow(dataException);

        CreateOrganizationRequest request =
                new CreateOrganizationRequest(
                        "Second Organization",
                        "Concurrent creation attempt"
                );

        assertThrows(
                ResourceConflictException.class,
                () -> organizationService.createOrganization(request)
        );

        verify(organizationRepository).saveAndFlush(any(Organization.class));
    }

    @Test
    void preservesUnrelatedConstraintViolation() {

        when(organizationRepository.findFirstBy())
                .thenReturn(Optional.empty());

        SQLException sqlException = new SQLException(
                "Organization name cannot be blank",
                "23514"
        );

        ConstraintViolationException constraintException =
                new ConstraintViolationException(
                        "Organization name check constraint violated",
                        sqlException,
                        "ck_organizations_name_nonblank"
                );

        DataIntegrityViolationException dataException =
                new DataIntegrityViolationException(
                        "Database constraint violation",
                        constraintException
                );

        when(organizationRepository.saveAndFlush(any(Organization.class)))
                .thenThrow(dataException);

        CreateOrganizationRequest request =
                new CreateOrganizationRequest(
                        "Test Organization",
                        "Unrelated database constraint"
                );

        DataIntegrityViolationException thrown = assertThrows(
                DataIntegrityViolationException.class,
                () -> organizationService.createOrganization(request)
        );

        assertSame(dataException, thrown);

        verify(organizationRepository).saveAndFlush(any(Organization.class));
    }

}
