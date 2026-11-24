package com.jamesstevens.epms.security.service;

import com.jamesstevens.epms.organization.repository.OrganizationRepository;
import com.jamesstevens.epms.user.entity.User;
import com.jamesstevens.epms.user.entity.UserCredential;
import com.jamesstevens.epms.user.repository.UserCredentialRepository;
import com.jamesstevens.epms.user.repository.UserRepository;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
public class EpmsUserDetailsService implements UserDetailsService {

    private static final String PASSWORD_ALGORITHM = "ARGON2ID";

    private final UserRepository userRepository;
    private final UserCredentialRepository credentialRepository;
    private final OrganizationRepository organizationRepository;

    public EpmsUserDetailsService(
            UserRepository userRepository,
            UserCredentialRepository credentialRepository,
            OrganizationRepository organizationRepository) {

        this.userRepository = Objects.requireNonNull(userRepository);
        this.credentialRepository =
                Objects.requireNonNull(credentialRepository);
        this.organizationRepository =
                Objects.requireNonNull(organizationRepository);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull UserDetails loadUserByUsername(
            @NonNull String email)
            throws UsernameNotFoundException {

        if (email.isBlank()) {
            throw authenticationFailure();
        }

        String normalizedEmail =
                email.trim().toLowerCase(Locale.ROOT);

        User user = userRepository
                .findByEmailIgnoreCase(normalizedEmail)
                .orElseThrow(
                        EpmsUserDetailsService::authenticationFailure
                );

        com.jamesstevens.epms.organization.entity.Organization organization = organizationRepository
                .findById(user.getOrganizationId())
                .orElseThrow(
                        EpmsUserDetailsService::authenticationFailure
                );

        if (!organization.isActive()) {
            throw authenticationFailure();
        }

        UserCredential credential = credentialRepository
                .findByUserId(user.getUserId())
                .orElseThrow(
                        EpmsUserDetailsService::authenticationFailure
                );

        if (!PASSWORD_ALGORITHM.equals(
                credential.getPasswordAlgorithmCode())) {
            throw authenticationFailure();
        }

        if (ProgressiveLoginLockoutPolicy.isBlocked(
                credential.getLoginLockoutState(), java.time.Instant.now())) {
            throw authenticationFailure();
        }

        if (!ProvisionalCredentialAccess.isAuthenticationAllowed(credential, java.time.Instant.now())) {
            throw authenticationFailure();
        }

        return org.springframework.security.core.userdetails.User
                .withUsername(user.getEmail())
                .password(credential.getPasswordHash())
                .disabled(!user.isActive() || user.isArchived())
                .authorities(List.of())
                .build();
    }

    private static UsernameNotFoundException authenticationFailure() {
        return new UsernameNotFoundException(
                "Invalid username or password"
        );
    }
}
