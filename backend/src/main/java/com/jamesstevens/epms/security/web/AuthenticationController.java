package com.jamesstevens.epms.security.web;

import com.jamesstevens.epms.user.repository.UserRepository;
import com.jamesstevens.epms.user.repository.UserCredentialRepository;
import com.jamesstevens.epms.user.service.UserCredentialService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthenticationController {

    private final UserRepository userRepository;
    private final UserCredentialRepository credentialRepository;
    private final UserCredentialService credentialService;

    public AuthenticationController(UserRepository userRepository,
                                    UserCredentialRepository credentialRepository,
                                    UserCredentialService credentialService) {
        this.userRepository = userRepository;
        this.credentialRepository = credentialRepository;
        this.credentialService = credentialService;
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of(
                "headerName", token.getHeaderName(),
                "parameterName", token.getParameterName(),
                "token", token.getToken()
        );
    }

    @GetMapping("/me")
    public AuthenticatedUserResponse currentUser(
            Authentication authentication) {

        com.jamesstevens.epms.user.entity.User user = userRepository
                .findByEmailIgnoreCase(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED
                ));

        return new AuthenticatedUserResponse(
                user.getUserId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                credentialRepository.findByUserId(user.getUserId())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED))
                        .isMustChangePassword()
        );
    }

    public record AuthenticatedUserResponse(
            UUID userId,
            String email,
            String firstName,
            String lastName,
            boolean mustChangePassword
    ) { }

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank String newPassword
    ) { }

    @PostMapping("/change-password")
    public org.springframework.http.ResponseEntity<Void> changePassword(
            Authentication authentication,
            @Valid @RequestBody ChangePasswordRequest body,
            HttpServletRequest request) {
        com.jamesstevens.epms.user.entity.User user = userRepository.findByEmailIgnoreCase(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        try {
            credentialService.changePassword(user.getUserId(),
                    body.currentPassword(), body.newPassword());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Password change rejected",
                    exception
            );
        }
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        return org.springframework.http.ResponseEntity.noContent().build();
    }
}
