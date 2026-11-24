package com.jamesstevens.epms.security.service;

import com.jamesstevens.epms.organization.entity.Organization;
import com.jamesstevens.epms.organization.repository.OrganizationRepository;
import com.jamesstevens.epms.user.entity.User;
import com.jamesstevens.epms.user.entity.UserCredential;
import com.jamesstevens.epms.user.repository.UserCredentialRepository;
import com.jamesstevens.epms.user.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EpmsUserDetailsServiceTest {

    private UserRepository userRepository;
    private UserCredentialRepository credentialRepository;
    private OrganizationRepository organizationRepository;
    private EpmsUserDetailsService service;

    private final UUID organizationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        credentialRepository = mock(UserCredentialRepository.class);
        organizationRepository = mock(OrganizationRepository.class);

        service = new EpmsUserDetailsService(
                userRepository,
                credentialRepository,
                organizationRepository
        );
    }

    private void stubEligibleAccount() {
        User user = mock(User.class);
        Organization organization = mock(Organization.class);

        when(user.getUserId()).thenReturn(userId);
        when(user.getOrganizationId()).thenReturn(organizationId);
        when(user.getEmail()).thenReturn("test@example.com");
        when(user.isActive()).thenReturn(true);

        when(organization.isActive()).thenReturn(true);

        when(userRepository.findByEmailIgnoreCase("test@example.com"))
                .thenReturn(Optional.of(user));

        when(organizationRepository.findById(organizationId))
                .thenReturn(Optional.of(organization));

        when(credentialRepository.findByUserId(userId))
                .thenReturn(Optional.of(new UserCredential(
                        userId,
                        "stored-argon2id-hash",
                        "ARGON2ID",
                        false
                )));
    }

    @Test
    void loadsEligibleUserCredentials() {
        stubEligibleAccount();

        var details = service.loadUserByUsername(
                "TEST@EXAMPLE.COM"
        );

        assertEquals("test@example.com", details.getUsername());
        assertEquals("stored-argon2id-hash", details.getPassword());
        assertTrue(details.isEnabled());
        assertTrue(details.getAuthorities().isEmpty());
    }

    @Test
    void rejectsExpiredProvisionalCredentialAtLogin() {
        stubEligibleAccount();
        UserCredential credential = new UserCredential(userId, "encoded", "ARGON2ID", false);
        java.time.OffsetDateTime issued = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).minusHours(13);
        credential.issueProvisionalPassword(issued, new byte[28], "test-key", UUID.randomUUID());
        when(credentialRepository.findByUserId(userId)).thenReturn(Optional.of(credential));
        assertThrows(UsernameNotFoundException.class,
                () -> service.loadUserByUsername("test@example.com"));
    }

    @Test
    void acceptsUnexpiredProvisionalCredentialAtLogin() {
        stubEligibleAccount();
        UserCredential credential = new UserCredential(userId, "encoded", "ARGON2ID", false);
        java.time.OffsetDateTime issued = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(5);
        credential.issueProvisionalPassword(issued, new byte[28], "test-key", UUID.randomUUID());
        when(credentialRepository.findByUserId(userId)).thenReturn(Optional.of(credential));
        assertEquals("test@example.com", service.loadUserByUsername("test@example.com").getUsername());
    }

    @Test
    void rejectsExplicitlyExpiredCredentialAtLogin() {
        stubEligibleAccount();
        UserCredential credential = new UserCredential(userId, "encoded", "ARGON2ID", false);
        java.time.OffsetDateTime issued = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).minusHours(13);
        credential.issueProvisionalPassword(issued, new byte[28], "test-key", UUID.randomUUID());
        assertTrue(credential.expireProvisionalPassword(issued.plusHours(12)));
        when(credentialRepository.findByUserId(userId)).thenReturn(Optional.of(credential));
        assertThrows(UsernameNotFoundException.class,
                () -> service.loadUserByUsername("test@example.com"));
    }

    @Test
    void rejectsUnknownEmail() {
        assertThrows(
                UsernameNotFoundException.class,
                () -> service.loadUserByUsername(
                        "unknown@example.com"
                )
        );
    }

    @Test
    void rejectsBlankEmail() {
        assertThrows(
                UsernameNotFoundException.class,
                () -> service.loadUserByUsername(" ")
        );

        verifyNoInteractions(
                userRepository,
                credentialRepository,
                organizationRepository
        );
    }

    @Test
    void rejectsInactiveOrganization() {
        stubEligibleAccount();

        Organization organization = mock(Organization.class);

        when(organization.isActive()).thenReturn(false);

        when(organizationRepository.findById(organizationId))
                .thenReturn(Optional.of(organization));

        assertThrows(
                UsernameNotFoundException.class,
                () -> service.loadUserByUsername(
                        "test@example.com"
                )
        );
    }

    @Test
    void rejectsMissingCredentials() {
        stubEligibleAccount();

        when(credentialRepository.findByUserId(userId))
                .thenReturn(Optional.empty());

        assertThrows(
                UsernameNotFoundException.class,
                () -> service.loadUserByUsername(
                        "test@example.com"
                )
        );
    }

    @Test
    void rejectsUnsupportedPasswordAlgorithm() {
        stubEligibleAccount();

        when(credentialRepository.findByUserId(userId))
                .thenReturn(Optional.of(new UserCredential(
                        userId,
                        "legacy-hash",
                        "LEGACY",
                        false
                )));

        assertThrows(
                UsernameNotFoundException.class,
                () -> service.loadUserByUsername(
                        "test@example.com"
                )
        );
    }

    @Test
    void disablesInactiveUser() {
        stubEligibleAccount();

        User user = userRepository
                .findByEmailIgnoreCase("test@example.com")
                .orElseThrow();

        when(user.isActive()).thenReturn(false);

        var details = service.loadUserByUsername(
                "test@example.com"
        );

        assertFalse(details.isEnabled());
    }

    @Test
    void disablesArchivedUser() {
        stubEligibleAccount();

        User user = userRepository
                .findByEmailIgnoreCase("test@example.com")
                .orElseThrow();

        when(user.isArchived()).thenReturn(true);

        var details = service.loadUserByUsername(
                "test@example.com"
        );

        assertFalse(details.isEnabled());
    }
}
