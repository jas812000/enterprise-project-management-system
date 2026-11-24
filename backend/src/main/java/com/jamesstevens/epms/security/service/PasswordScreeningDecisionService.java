package com.jamesstevens.epms.security.service;

import java.util.Objects;

/**
 * Classifies a new password without persisting it. Outages require explicit
 * provisional handling; callers must never silently treat them as VERIFIED.
 */
public final class PasswordScreeningDecisionService {
    public enum Decision { VERIFIED, REJECT_COMPROMISED, PROVISIONAL_REQUIRED }
    private final PwnedPasswordScreeningService screening;

    public PasswordScreeningDecisionService(PwnedPasswordScreeningService screening) {
        this.screening = Objects.requireNonNull(screening);
    }

    public Decision decide(String rawPassword) {
        String normalized = PasswordPolicy.normalizeAndValidate(rawPassword);
        Decision decision;
        try {
            decision = screening.screen(normalized) == PwnedPasswordScreeningService.Result.COMPROMISED
                    ? Decision.REJECT_COMPROMISED : Decision.VERIFIED;
        } catch (PwnedPasswordScreeningService.ScreeningUnavailableException unavailable) {
            decision = Decision.PROVISIONAL_REQUIRED;
        }
        return decision;
    }
}
