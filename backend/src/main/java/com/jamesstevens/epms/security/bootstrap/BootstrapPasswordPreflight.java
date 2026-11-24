package com.jamesstevens.epms.security.bootstrap;

import com.jamesstevens.epms.security.service.PasswordPolicy;
import com.jamesstevens.epms.security.service.PasswordScreeningDecisionService;
import java.util.Objects;

/** Fail-closed password eligibility check for first-administrator provisioning. */
public final class BootstrapPasswordPreflight {
    private final PasswordScreeningDecisionService screening;

    public BootstrapPasswordPreflight(PasswordScreeningDecisionService screening) {
        this.screening = Objects.requireNonNull(screening);
    }

    /** Returns the normalized password only when screening definitively verifies it. */
    public String requireVerified(String password) {
        String normalized = PasswordPolicy.normalizeAndValidate(password);
        PasswordScreeningDecisionService.Decision decision = screening.decide(normalized);
        if (decision != PasswordScreeningDecisionService.Decision.VERIFIED) {
            throw new IllegalArgumentException("Bootstrap password screening did not verify the password");
        }
        return normalized;
    }
}
