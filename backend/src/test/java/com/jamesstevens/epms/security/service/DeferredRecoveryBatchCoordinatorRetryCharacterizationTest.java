package com.jamesstevens.epms.security.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** B18D regression: a failed claim cannot starve another claim in the same batch. */
class DeferredRecoveryBatchCoordinatorRetryCharacterizationTest {
    @Test void failedOldestClaimDoesNotBlockLaterClaim() {
        var claims = mock(DeferredRecoveryClaimService.class);
        var recovery = mock(DeferredClaimedRecoveryService.class);
        var a = new DeferredRecoveryClaimService.Claim(UUID.randomUUID(), UUID.randomUUID());
        var b = new DeferredRecoveryClaimService.Claim(UUID.randomUUID(), UUID.randomUUID());
        when(claims.claim(2)).thenReturn(List.of(a, b));
        when(recovery.resolveClaimed(a)).thenReturn(DeferredCredentialScreeningService.Outcome.UNAVAILABLE);
        when(recovery.resolveClaimed(b)).thenReturn(DeferredCredentialScreeningService.Outcome.VERIFIED);
        var result = new DeferredRecoveryBatchCoordinator(claims, recovery).process(2);
        assertEquals(1, result.unavailable());
        assertEquals(1, result.verified());
    }
}
