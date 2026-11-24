package com.jamesstevens.epms.security.web;

import com.jamesstevens.epms.security.service.AuthenticationSecurityAuditService;
import com.jamesstevens.epms.organization.repository.OrganizationRepository;
import com.jamesstevens.epms.user.repository.UserCredentialRepository;
import com.jamesstevens.epms.user.repository.UserRepository;
import com.jamesstevens.epms.user.service.UserCredentialService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import java.io.PrintWriter;
import java.io.StringWriter;
import static org.mockito.Mockito.*;

class EpmsAuthenticationFailureHandlerAuditTest {
    @Test void auditOutageReturnsGeneric503() throws Exception {
        var audit = mock(AuthenticationSecurityAuditService.class);
        var request = mock(HttpServletRequest.class);
        var response = mock(HttpServletResponse.class);
        var output = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(output));
        doThrow(new IllegalStateException("sensitive internal diagnostic"))
                .when(audit).recordRejectedLogin(request);
        var handler = new EpmsAuthenticationFailureHandler(
                mock(UserRepository.class), mock(UserCredentialRepository.class),
                mock(OrganizationRepository.class), mock(UserCredentialService.class), audit);
        handler.onAuthenticationFailure(request, response, new BadCredentialsException("invalid"));
        verify(response).setStatus(503);
        org.junit.jupiter.api.Assertions.assertTrue(output.toString().contains("temporarily unavailable"));
        org.junit.jupiter.api.Assertions.assertFalse(output.toString().contains("sensitive internal diagnostic"));
    }
}
