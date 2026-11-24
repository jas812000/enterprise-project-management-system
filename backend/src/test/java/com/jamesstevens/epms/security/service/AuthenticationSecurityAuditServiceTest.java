package com.jamesstevens.epms.security.service;

import com.jamesstevens.epms.user.repository.UserCredentialRepository;
import com.jamesstevens.epms.user.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.Optional;
import java.util.UUID;

class AuthenticationSecurityAuditServiceTest {
    private static final String KEY = "unit-test-hmac-key-must-be-long-enough-123456";
    @Test void unknownAccountIsAuditedWithoutPlaintextEmail() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UserRepository users = mock(UserRepository.class);
        UserCredentialRepository credentials = mock(UserCredentialRepository.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getParameter("username")).thenReturn(" Unknown@Example.com ");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(users.findByEmailIgnoreCase("unknown@example.com")).thenReturn(Optional.empty());
        new AuthenticationSecurityAuditService(jdbc, users, credentials, KEY).recordRejectedLogin(request);
        verify(jdbc).update(contains("INSERT INTO authentication_security_events"),
                eq("LOGIN_FAILED"), isNull(), isNull(), argThat(value ->
                        value instanceof String && ((String)value).matches("[0-9a-f]{64}")),
                eq("127.0.0.1"), isNull(), eq(0), eq(0), isNull(), eq("REJECTED"));
        verifyNoInteractions(credentials);
    }
    @Test void requiredAuditWriteFailurePropagates() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UserRepository users = mock(UserRepository.class);
        UserCredentialRepository credentials = mock(UserCredentialRepository.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getParameter("username")).thenReturn("missing@example.com");
        when(users.findByEmailIgnoreCase("missing@example.com")).thenReturn(Optional.empty());
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("audit offline"));
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.DataAccessResourceFailureException.class,
                () -> new AuthenticationSecurityAuditService(jdbc, users, credentials, KEY)
                        .recordRejectedLogin(request));
    }
    @Test void rateLimitedAuditUsesDedicatedSourceDigestAndCorrelation() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var service = new AuthenticationSecurityAuditService(jdbc,
                mock(UserRepository.class), mock(UserCredentialRepository.class), KEY);
        var request = mock(HttpServletRequest.class);
        UUID correlation = UUID.randomUUID();
        when(request.getAttribute("epms.correlationId")).thenReturn(correlation);
        when(request.getHeader("User-Agent")).thenReturn("test-agent");
        service.recordRateLimited("LOGIN", "a".repeat(64), request);
        verify(jdbc).update(contains("source_ip_hmac"), eq("a".repeat(64)),
                eq("test-agent"), eq(correlation), eq("LOGIN"));
        verify(request, never()).getRemoteAddr();
    }

    @Test void rateLimitedAuditRejectsInvalidInputAndPropagatesStorageFailure() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var service = new AuthenticationSecurityAuditService(jdbc,
                mock(UserRepository.class), mock(UserCredentialRepository.class), KEY);
        var request = mock(HttpServletRequest.class);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.recordRateLimited("UNRECOGNIZED", "a".repeat(64), request));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.recordRateLimited("LOGIN", "invalid", request));
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("audit offline"));
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.DataAccessResourceFailureException.class,
                () -> service.recordRateLimited("LOGIN", "a".repeat(64), request));
    }

    @Test void blockedCredentialIsNotIncremented() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UserRepository users = mock(UserRepository.class);
        UserCredentialRepository credentials = mock(UserCredentialRepository.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        var user = mock(com.jamesstevens.epms.user.entity.User.class);
        UUID userId = UUID.randomUUID();
        when(user.getUserId()).thenReturn(userId);
        when(request.getParameter("username")).thenReturn("person@example.com");
        when(users.findByEmailIgnoreCase("person@example.com")).thenReturn(Optional.of(user));
        var credential = new com.jamesstevens.epms.user.entity.UserCredential(userId,"encoded","ARGON2ID",false);
        credential.applyLoginLockoutState(new ProgressiveLoginLockoutPolicy.State(3,2,null,true));
        when(credentials.findByUserIdForUpdate(userId)).thenReturn(Optional.of(credential));
        new AuthenticationSecurityAuditService(jdbc,users,credentials,KEY).recordRejectedLogin(request);
        verify(credentials,never()).saveAndFlush(any());
        verify(jdbc).update(contains("INSERT INTO authentication_security_events"),
                eq("LOGIN_REJECTED_LOCKED"),eq(userId),isNull(),anyString(),isNull(),isNull(),eq(3),eq(2),isNull(),eq("REJECTED"));
    }
}
