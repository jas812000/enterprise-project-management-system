package com.jamesstevens.epms.team.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Authorized metadata only; confidential reasons are never included. */
public record TeamSecurityAlert(UUID alertId, UUID teamId, String alertCode, OffsetDateTime createdAt,
                                OffsetDateTime acknowledgedAt, OffsetDateTime resolvedAt, boolean acknowledgedByAny,
                                int escalationCount, OffsetDateTime lastEscalatedAt, String lastEscalationStage) { }
