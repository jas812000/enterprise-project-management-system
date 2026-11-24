package com.jamesstevens.epms.security.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.HexFormat;

/** PostgreSQL-backed source-IP enforcement. Never persists raw IP addresses. */
@Service
public class AuthenticationRateLimitService {
    private static final int LOGIN_MINUTE_LIMIT = 20;
    private static final int OTHER_MINUTE_LIMIT = 10;
    private static final int LOGIN_HOUR_LIMIT = 100;
    private static final int OTHER_HOUR_LIMIT = 10;
    private static final int MINIMUM_HMAC_KEY_BYTES = 32;
    private static final int BLOCK_DURATION_MINUTES = 5;
    private final AuthenticationRateLimitCounterRepository counters;
    private final byte[] hmacKey;

    public AuthenticationRateLimitService(AuthenticationRateLimitCounterRepository counters,
            @Value("${epms.security.rate-limit.hmac-key:${EPMS_RATE_LIMIT_HMAC_KEY:}}") String key) {
        this.counters = counters;
        this.hmacKey = key == null ? new byte[0] : key.getBytes(StandardCharsets.UTF_8);
    }

    public AuthenticationRateLimitCounterRepository.Decision consume(HttpServletRequest request, String policy) {
        String source = sourceDigest(request);
        int minute = "LOGIN".equals(policy) ? LOGIN_MINUTE_LIMIT : OTHER_MINUTE_LIMIT;
        int hour = "LOGIN".equals(policy) ? LOGIN_HOUR_LIMIT : OTHER_HOUR_LIMIT;
        return counters.consume(source, policy, minute, hour, Duration.ofMinutes(BLOCK_DURATION_MINUTES));
    }

    /** The same privacy-preserving source key is used for counters and audit events. */
    public String sourceDigest(HttpServletRequest request) {
        if (hmacKey.length < MINIMUM_HMAC_KEY_BYTES) {
            throw new IllegalStateException("EPMS_RATE_LIMIT_HMAC_KEY must contain at least 32 UTF-8 bytes");
        }
        String remote = request.getRemoteAddr();
        if (remote == null || remote.isBlank()) {
            throw new IllegalStateException("Client network address unavailable");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(remote.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Cannot protect rate-limit source", exception);
        }
    }
}
