package com.jamesstevens.epms.security.service;

import java.util.Optional;
import com.jamesstevens.epms.user.entity.User;
import com.jamesstevens.epms.user.entity.UserCredential;
import com.jamesstevens.epms.user.repository.UserCredentialRepository;
import com.jamesstevens.epms.user.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/** Security event writes and credential state changes share one PostgreSQL transaction. */
@Service
public class AuthenticationSecurityAuditService {
    private static final String OUTCOME_REJECTED = "REJECTED";
    private static final int MIN_AUDIT_HMAC_KEY_LENGTH = 32;
    private static final int MAX_USER_AGENT_LENGTH = 300;
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final UserCredentialRepository credentials;
    private final String lookupKey;

    public AuthenticationSecurityAuditService(JdbcTemplate jdbc, UserRepository users,
            UserCredentialRepository credentials,
            @Value("${epms.security.audit.hmac-key:${EPMS_AUTH_AUDIT_HMAC_KEY:}}") String lookupKey) {
        this.jdbc = jdbc;
        this.users = users;
        this.credentials = credentials;
        this.lookupKey = lookupKey;
    }

    /** The HMAC key must be provisioned separately from the application source. */
    private String digest(String email) {
        if (lookupKey == null || lookupKey.length() < MIN_AUDIT_HMAC_KEY_LENGTH) {
            throw new IllegalStateException("EPMS_AUTH_AUDIT_HMAC_KEY must be at least 32 characters");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(lookupKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(email.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("Cannot protect account lookup", ex);
        }
    }

    public static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    /** Inserts only fixed event types and metadata; never raw email or credentials. */
    public void event(String type, UUID target, UUID actor, String hmac,
                      HttpServletRequest request, int stage, int failures,
                      String reason, String outcome) {
        String remote = request == null ? null : request.getRemoteAddr();
        String agent = request == null ? null : request.getHeader("User-Agent");
        if (agent != null && agent.length() > MAX_USER_AGENT_LENGTH) {
            agent = agent.substring(0, MAX_USER_AGENT_LENGTH);
        }
        jdbc.update("""
                INSERT INTO authentication_security_events
                (event_type,target_user_id,actor_user_id,account_lookup_hmac,
                 source_ip,user_agent,lockout_stage,stage_failure_count,reason,outcome)
                VALUES (?,?,?,?,CAST(? AS inet),?,?,?,?,?)
                """, type, target, actor, hmac, remote, agent, stage, failures, reason, outcome);
    }

    /** Mandatory, independent audit write. Rate-limit events never store a raw source IP. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRateLimited(String policy, String sourceHmac, HttpServletRequest request) {
        if (!java.util.Set.of("LOGIN", "CHANGE_PASSWORD", "ADMIN_UNLOCK").contains(policy)) {
            throw new IllegalArgumentException("Unsupported rate-limit policy");
        }
        if (sourceHmac == null || !sourceHmac.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid source digest");
        }
        String agent = request == null ? null : request.getHeader("User-Agent");
        if (agent != null && agent.length() > MAX_USER_AGENT_LENGTH) {
            agent = agent.substring(0, MAX_USER_AGENT_LENGTH);
        }
        Object requestId = request == null ? null : request.getAttribute("epms.correlationId");
        UUID correlation = requestId instanceof UUID uuid ? uuid : UUID.randomUUID();
        jdbc.update("""
                INSERT INTO authentication_security_events
                (event_type, source_ip_hmac, source_ip, user_agent, correlation_id, reason, outcome)
                VALUES ('RATE_LIMITED', ?, NULL, ?, ?, ?, 'REJECTED')
                """, sourceHmac, agent, correlation, policy);
    }

    /** Each rejected HTTP login, including unknown accounts, creates one event. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRejectedLogin(HttpServletRequest request) {
        String email = normalize(request.getParameter("username"));
        String hmac = digest(email);
        Optional<User> maybeUser = email.isBlank() ? Optional.<User>empty()
                : users.findByEmailIgnoreCase(email);
        if (maybeUser.isEmpty()) {
            event("LOGIN_FAILED", null, null, hmac, request, 0, 0, null, OUTCOME_REJECTED);
        } else {
            UUID userId = maybeUser.get().getUserId();
            Optional<UserCredential> maybeCredential = credentials.findByUserIdForUpdate(userId);
            if (maybeCredential.isEmpty()) {
                event("LOGIN_FAILED", userId, null, hmac, request, 0, 0, null, OUTCOME_REJECTED);
            } else {
                UserCredential credential = maybeCredential.get();
                Instant now = Instant.now();
                boolean blocked = ProgressiveLoginLockoutPolicy.isBlocked(credential.getLoginLockoutState(), now);
                if (!blocked && maybeUser.get().isActive() && !maybeUser.get().isArchived()
                        && "ARGON2ID".equals(credential.getPasswordAlgorithmCode())) {
                    credential.registerLoginFailure(now);
                    credentials.saveAndFlush(credential);
                }
                ProgressiveLoginLockoutPolicy.State state = credential.getLoginLockoutState();
                String type = blocked ? "LOGIN_REJECTED_LOCKED"
                        : ProgressiveLoginLockoutPolicy.isBlocked(state, now) ? "LOGIN_LOCKED" : "LOGIN_FAILED";
                event(type, userId, null, hmac, request,
                        state.stage(), state.failuresInStage(), null, OUTCOME_REJECTED);
            }
        }
    }

    /** Successful authentication clears escalation; audit and state update are atomic. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordSuccessfulLogin(UUID userId, HttpServletRequest request) {
        UserCredential credential = credentials.findByUserIdForUpdate(userId).orElseThrow();
        Instant now = Instant.now();
        boolean accepted = !ProgressiveLoginLockoutPolicy.isBlocked(credential.getLoginLockoutState(), now);
        if (accepted) {
            credential.resetLoginLockoutAfterSuccess(now);
            credentials.saveAndFlush(credential);
            event("LOGIN_SUCCEEDED", userId, null, null, request, 0, 0, null, "ACCEPTED");
        } else {
            ProgressiveLoginLockoutPolicy.State state = credential.getLoginLockoutState();
            event("LOGIN_REJECTED_LOCKED", userId, null, null, request,
                    state.stage(), state.failuresInStage(), null, OUTCOME_REJECTED);
        }
        return accepted;
    }

    /** Called only from the unlock service's transaction. */
    public void recordUnlock(String type, UUID target, UUID actor,
                             HttpServletRequest request, int stage, int failures,
                             String reason, String outcome) {
        event(type, target, actor, null, request, stage, failures, reason, outcome);
    }
}
