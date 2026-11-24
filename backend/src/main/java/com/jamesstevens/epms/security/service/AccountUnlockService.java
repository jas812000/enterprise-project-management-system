package com.jamesstevens.epms.security.service;

import java.util.Optional;
import com.jamesstevens.epms.user.entity.User;
import com.jamesstevens.epms.user.entity.UserCredential;
import com.jamesstevens.epms.user.repository.UserCredentialRepository;
import com.jamesstevens.epms.user.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** Permission-checked administrator recovery, with mandatory transactional audit. */
@Service
public class AccountUnlockService {
    private static final int MAX_UNLOCK_REASON_LENGTH = 500;
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final UserCredentialRepository credentials;
    private final AuthenticationSecurityAuditService audit;

    public AccountUnlockService(JdbcTemplate jdbc, UserRepository users,
            UserCredentialRepository credentials, AuthenticationSecurityAuditService audit) {
        this.jdbc = jdbc;
        this.users = users;
        this.credentials = credentials;
        this.audit = audit;
    }

    @Transactional
    public boolean unlock(Authentication authentication, UUID target,
                          String reason, HttpServletRequest request) {
        Optional<User> actor = Optional.empty();
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof
                        org.springframework.security.authentication.AnonymousAuthenticationToken)) {
            actor = users.findByEmailIgnoreCase(authentication.getName());
        }
        UUID actorId = actor.map(User::getUserId).orElse(null);
        boolean allowed = actorId != null && actor.get().isActive() && !actor.get().isArchived()
                && Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS(SELECT 1 FROM account_unlock_grants
                                      WHERE user_id = ? AND revoked_at IS NULL)
                        """, Boolean.class, actorId));
        boolean validReason = reason != null && !reason.isBlank() && reason.length() <= MAX_UNLOCK_REASON_LENGTH;
        // Acquire the same per-user transaction lock as V20 issuance before
        // findByUserIdForUpdate; avoid row-lock/advisory-lock inversion.
        if (target != null) {
            jdbc.queryForObject(
                    "SELECT pg_catalog.pg_advisory_xact_lock(" +
                    "pg_catalog.hashtextextended(CAST(? AS text), 82302026))",
                    String.class, target.toString());
        }
        Optional<UserCredential> credential = target == null
                ? Optional.<UserCredential>empty()
                : credentials.findByUserIdForUpdate(target);
        int stage = credential.map(c -> c.getLoginLockoutState().stage()).orElse(0);
        int failures = credential.map(c -> c.getLoginLockoutState().failuresInStage()).orElse(0);
        boolean accepted = allowed && validReason && credential.isPresent();
        if (accepted) {
            credential.get().administratorUnlockLogin();
            credentials.saveAndFlush(credential.get());
            audit.recordUnlock("ACCOUNT_UNLOCK", target, actorId, request,
                    stage, failures, reason.trim(), "ACCEPTED");
        } else {
            // V7 requires an actor for ACCOUNT_UNLOCK_* events. Unauthenticated
            // requests are blocked by the security chain before this service.
            // Fail closed if this method is called without a resolvable actor.
            if (actorId == null) {
                throw new org.springframework.security.access.AccessDeniedException(
                        "Authenticated administrator required");
            }
            String rejectionReason = "TARGET_NOT_FOUND";
            boolean permissionDenied = !allowed;
            if (permissionDenied) {
                rejectionReason = "PERMISSION_DENIED";
            } else if (!validReason) {
                rejectionReason = "INVALID_REASON";
            }
            UUID auditedTarget = null;
            if (target != null && users.existsById(target)) {
                auditedTarget = target;
            }
            audit.recordUnlock("ACCOUNT_UNLOCK_REJECTED",
                    auditedTarget, actorId, request, stage, failures, rejectionReason, "REJECTED");
        }
        return accepted;
    }
}
