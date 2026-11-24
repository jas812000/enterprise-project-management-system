
package com.jamesstevens.epms.security.service;

import com.jamesstevens.epms.organization.entity.Organization;
import com.jamesstevens.epms.organization.repository.OrganizationRepository;
import com.jamesstevens.epms.user.repository.UserRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Objects;

@Service
public class AuthenticatedSessionValidationService {

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;

    public AuthenticatedSessionValidationService(
            UserRepository userRepository,
            OrganizationRepository organizationRepository) {

        this.userRepository = Objects.requireNonNull(userRepository);
        this.organizationRepository =
                Objects.requireNonNull(organizationRepository);
    }

    @Transactional(readOnly = true)
    public boolean isEligible(String email) {

        boolean eligible = false;
        if (email != null && !email.isBlank()) {
            String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
            eligible = userRepository.findByEmailIgnoreCase(normalizedEmail)
                    .filter(user -> user.isActive() && !user.isArchived())
                    .flatMap(user -> organizationRepository
                            .findById(user.getOrganizationId()))
                    .map(Organization::isActive)
                    .orElse(false);
        }
        return eligible;
    }
}
