package com.jamesstevens.epms.security.service;

import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** Explicit bounded recovery. Each claim and result commits independently. */
@Service
public class DeferredRecoveryBatchCoordinator {
    private static final int MAX_RECOVERY_BATCH_SIZE = 100;
    private final DeferredRecoveryClaimService claims;
    private final DeferredClaimedRecoveryService recovery;

    public record Result(int attempted, int verified, int changeRequired,
                         int unavailable, int notPending, int failed) { }

    public DeferredRecoveryBatchCoordinator(DeferredRecoveryClaimService claims,
                                            DeferredClaimedRecoveryService recovery) {
        this.claims = Objects.requireNonNull(claims);
        this.recovery = Objects.requireNonNull(recovery);
    }

    public Result process(int limit) {
        if (limit < 1 || limit > MAX_RECOVERY_BATCH_SIZE) {
            throw new IllegalArgumentException("Recovery batch limit must be between 1 and 100");
        }
        List<DeferredRecoveryClaimService.Claim> batch = claims.claim(limit);
        int verified = 0;
        int changeRequired = 0;
        int unavailable = 0;
        int notPending = 0;
        int failed = 0;
        for (DeferredRecoveryClaimService.Claim claim : batch) {
            try {
                switch (recovery.resolveClaimed(claim)) {
                    case VERIFIED -> verified++;
                    case CHANGE_REQUIRED -> changeRequired++;
                    case UNAVAILABLE -> unavailable++;
                    case NOT_PENDING -> notPending++;
                    default -> failed++;
                }
            } catch (RuntimeException error) { // NOPMD - Isolate each failed recovery claim.
                // Never log exception messages: cryptographic details may be sensitive.
                failed++;
            } finally {
                try { claims.release(claim); }
                catch (RuntimeException ignored) { // NOPMD - Release failure must not stop remaining claims.
                    failed++;
                }
            }
        }
        return new Result(batch.size(), verified, changeRequired, unavailable, notPending, failed);
    }
}
