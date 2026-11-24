package com.jamesstevens.epms.security.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class DeferredRecoveryClaimServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final DeferredRecoveryClaimService service = new DeferredRecoveryClaimService(
            jdbc, Clock.fixed(Instant.parse("2026-11-20T20:00:00Z"), ZoneOffset.UTC));

    @Test void rejectsInvalidBatchSizesWithoutDatabaseAccess() {
        assertThrows(IllegalArgumentException.class, () -> service.claim(0));
        assertThrows(IllegalArgumentException.class, () -> service.claim(101));
        verifyNoInteractions(jdbc);
    }

    @Test void claimsEligibleIdsWithOneSharedToken() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), eq(UUID.class), any(Object[].class)))
                .thenReturn(List.of(first, second));
        var claims = service.claim(2);
        assertEquals(2, claims.size());
        assertEquals(first, claims.get(0).credentialId());
        assertEquals(second, claims.get(1).credentialId());
        assertEquals(claims.get(0).token(), claims.get(1).token());
        verify(jdbc).queryForList(argThat(sql -> sql.contains("SKIP LOCKED")
                && sql.contains("screening_claim_expires_at")
                && sql.contains("provisional_expires_at >")), eq(UUID.class), any(Object[].class));
    }

    @Test void releaseRequiresMatchingToken() {
        var claim = new DeferredRecoveryClaimService.Claim(UUID.randomUUID(), UUID.randomUUID());
        when(jdbc.update(anyString(), eq(claim.credentialId()), eq(claim.token()))).thenReturn(1);
        assertTrue(service.release(claim));
        verify(jdbc).update(argThat(sql -> sql.contains("screening_claim_token = ?")),
                eq(claim.credentialId()), eq(claim.token()));
    }

    @Test void staleReleaseIsRejected() {
        var claim = new DeferredRecoveryClaimService.Claim(UUID.randomUUID(), UUID.randomUUID());
        assertFalse(service.release(claim));
    }
}
