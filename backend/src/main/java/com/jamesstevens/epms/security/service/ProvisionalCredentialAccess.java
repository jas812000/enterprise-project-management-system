package com.jamesstevens.epms.security.service;

import com.jamesstevens.epms.user.entity.UserCredential;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;

/** Shared login and authenticated-session enforcement of provisional screening expiry. */
public final class ProvisionalCredentialAccess {
    private static final String PENDING_VERIFICATION = "PENDING_VERIFICATION";

    private ProvisionalCredentialAccess() { }

    public static boolean isAuthenticationAllowed(UserCredential credential, Instant now) {
        Objects.requireNonNull(credential, "credential");
        Objects.requireNonNull(now, "now");
        String state = credential.getScreeningStatus();
        boolean allowed = "VERIFIED".equals(state) || "CHANGE_REQUIRED".equals(state);
        if (PENDING_VERIFICATION.equals(state)) {
            OffsetDateTime issued = credential.getProvisionalIssuedAt();
            OffsetDateTime expires = credential.getProvisionalExpiresAt();
            allowed = issued != null && expires != null
                    && issued.toInstant().plus(ProvisionalCredentialLifecycle.VALIDITY)
                            .equals(expires.toInstant())
                    && now.isBefore(expires.toInstant());
        }
        return allowed;
    }
}
