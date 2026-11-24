package com.jamesstevens.epms.security.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeferredRecoveryBatchCoordinatorTest {
    private final DeferredRecoveryClaimService claims = mock(DeferredRecoveryClaimService.class);
    private final DeferredClaimedRecoveryService recovery = mock(DeferredClaimedRecoveryService.class);
    private final DeferredRecoveryBatchCoordinator coordinator = new DeferredRecoveryBatchCoordinator(claims, recovery);

    private static DeferredRecoveryClaimService.Claim claim() {
        return new DeferredRecoveryClaimService.Claim(UUID.randomUUID(), UUID.randomUUID());
    }

    @Test void rejectsUnboundedRequests() {
        assertThrows(IllegalArgumentException.class, () -> coordinator.process(0));
        assertThrows(IllegalArgumentException.class, () -> coordinator.process(101));
        verifyNoInteractions(claims, recovery);
    }

    @Test void aggregatesAndReleasesClaims() {
        var a = claim(); var b = claim();
        when(claims.claim(2)).thenReturn(List.of(a, b));
        when(recovery.resolveClaimed(a)).thenReturn(DeferredCredentialScreeningService.Outcome.VERIFIED);
        when(recovery.resolveClaimed(b)).thenReturn(DeferredCredentialScreeningService.Outcome.UNAVAILABLE);
        var result = coordinator.process(2);
        assertEquals(2, result.attempted());
        assertEquals(1, result.verified());
        assertEquals(1, result.unavailable());
        verify(claims).release(a);
        verify(claims).release(b);
    }

    @Test void failureDoesNotPreventNextClaim() {
        var a = claim(); var b = claim();
        when(claims.claim(2)).thenReturn(List.of(a, b));
        when(recovery.resolveClaimed(a)).thenThrow(new IllegalStateException("test failure"));
        when(recovery.resolveClaimed(b)).thenReturn(DeferredCredentialScreeningService.Outcome.CHANGE_REQUIRED);
        var result = coordinator.process(2);
        assertEquals(1, result.failed());
        assertEquals(1, result.changeRequired());
        verify(claims).release(a);
        verify(claims).release(b);
    }

    @Test void emptyQueueIsNoOp() {
        when(claims.claim(25)).thenReturn(List.of());
        assertEquals(0, coordinator.process(25).attempted());
        verifyNoInteractions(recovery);
    }
}
