package com.jamesstevens.epms.team.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Administrative access metadata only; no original content or free-text justification. */
public record TeamConfidentialAccessRecord(UUID accessId, UUID actorUserId, UUID requestedTeamId,
        UUID requestedEventId, String sourceKind, String outcome, String accessScope, OffsetDateTime occurredAt) { }
