package com.jamesstevens.epms.security.service;

import com.jamesstevens.epms.user.repository.UserCredentialRepository;
import com.jamesstevens.epms.user.repository.UserRepository;
import com.jamesstevens.epms.user.entity.UserCredential;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import java.util.Optional;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class AccountUnlockServiceTest {
    @Test void noPermissionRejectsAndAuditsWithoutModifyingCredential() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UserRepository users = mock(UserRepository.class);
        UserCredentialRepository credentials = mock(UserCredentialRepository.class);
        AuthenticationSecurityAuditService audit = mock(AuthenticationSecurityAuditService.class);
        Authentication auth = mock(Authentication.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        UUID actorId=UUID.randomUUID(),target=UUID.randomUUID();
        var actor = mock(com.jamesstevens.epms.user.entity.User.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getName()).thenReturn("admin@example.com");
        when(users.findByEmailIgnoreCase("admin@example.com")).thenReturn(Optional.of(actor));
        when(actor.getUserId()).thenReturn(actorId);
        when(actor.isActive()).thenReturn(true);
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),eq(actorId))).thenReturn(false);
        when(users.existsById(target)).thenReturn(true);
        assertFalse(new AccountUnlockService(jdbc,users,credentials,audit)
                .unlock(auth,target,"reason",request));
        verify(audit).recordUnlock(eq("ACCOUNT_UNLOCK_REJECTED"),eq(target),eq(actorId),
                eq(request),eq(0),eq(0),eq("PERMISSION_DENIED"),eq("REJECTED"));
        verify(credentials,never()).saveAndFlush(any());
    }
    @Test void unlockAcquiresAdvisoryLockBeforeCredentialRowLock() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UserRepository users = mock(UserRepository.class);
        UserCredentialRepository credentials = mock(UserCredentialRepository.class);
        AuthenticationSecurityAuditService audit = mock(AuthenticationSecurityAuditService.class);
        Authentication auth = mock(Authentication.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        UUID actorId = UUID.randomUUID(), target = UUID.randomUUID();
        var actor = mock(com.jamesstevens.epms.user.entity.User.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getName()).thenReturn("admin@example.com");
        when(users.findByEmailIgnoreCase("admin@example.com")).thenReturn(Optional.of(actor));
        when(actor.getUserId()).thenReturn(actorId);
        when(actor.isActive()).thenReturn(true);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(actorId))).thenReturn(true);
        var credential = new UserCredential(target, "encoded", "ARGON2ID", false);
        when(credentials.findByUserIdForUpdate(target)).thenReturn(Optional.of(credential));
        assertTrue(new AccountUnlockService(jdbc, users, credentials, audit)
                .unlock(auth, target, "ticket", request));
        var ordered = inOrder(jdbc, credentials);
        ordered.verify(jdbc).queryForObject(contains("pg_advisory_xact_lock"),
                eq(String.class), eq(target.toString()));
        ordered.verify(credentials).findByUserIdForUpdate(target);
        ordered.verify(credentials).saveAndFlush(credential);
    }

    @Test void permissionAndReasonUnlockAndAudit() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UserRepository users = mock(UserRepository.class);
        UserCredentialRepository credentials = mock(UserCredentialRepository.class);
        AuthenticationSecurityAuditService audit = mock(AuthenticationSecurityAuditService.class);
        Authentication auth = mock(Authentication.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        UUID actorId=UUID.randomUUID(),target=UUID.randomUUID();
        var actor = mock(com.jamesstevens.epms.user.entity.User.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getName()).thenReturn("admin@example.com");
        when(users.findByEmailIgnoreCase("admin@example.com")).thenReturn(Optional.of(actor));
        when(actor.getUserId()).thenReturn(actorId);
        when(actor.isActive()).thenReturn(true);
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),eq(actorId))).thenReturn(true);
        var credential = new UserCredential(target,"encoded","ARGON2ID",false);
        credential.applyLoginLockoutState(new ProgressiveLoginLockoutPolicy.State(3,2,null,true));
        when(credentials.findByUserIdForUpdate(target)).thenReturn(Optional.of(credential));
        assertTrue(new AccountUnlockService(jdbc,users,credentials,audit)
                .unlock(auth,target,"  helpdesk ticket 123  ",request));
        assertEquals(ProgressiveLoginLockoutPolicy.initial(),credential.getLoginLockoutState());
        verify(credentials).saveAndFlush(credential);
        verify(audit).recordUnlock(eq("ACCOUNT_UNLOCK"),eq(target),eq(actorId),
                eq(request),eq(3),eq(2),eq("helpdesk ticket 123"),eq("ACCEPTED"));
    }
}
