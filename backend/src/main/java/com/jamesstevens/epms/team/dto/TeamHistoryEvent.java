package com.jamesstevens.epms.team.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Authorized metadata only; confidential reasons are never included. */
public record TeamHistoryEvent(UUID eventId, String sourceKind, String eventKind, OffsetDateTime occurredAt) { }
