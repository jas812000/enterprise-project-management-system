package com.jamesstevens.epms.security.web;

import com.jamesstevens.epms.security.service.AccountUnlockService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

/** Requires an authenticated session, CSRF token, explicit grant, and justification. */
@RestController
@RequestMapping("/api/auth/admin")
public class AccountUnlockController {
    private final AccountUnlockService unlockService;
    public AccountUnlockController(AccountUnlockService unlockService) {
        this.unlockService = unlockService;
    }
    public record UnlockRequest(String reason) { }
    @PostMapping("/users/{userId}/unlock")
    public ResponseEntity<Void> unlock(@PathVariable UUID userId,
            @RequestBody(required = false) UnlockRequest body, Authentication authentication,
            HttpServletRequest request) {
        boolean allowed = unlockService.unlock(authentication, userId,
                body == null ? null : body.reason(), request);
        return allowed ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }
}
