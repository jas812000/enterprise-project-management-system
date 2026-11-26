package com.jamesstevens.epms.team.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Administrative dispatch diagnostics; never contains a raw database error message. */
public record TeamDispatchFailure(UUID sourceId, UUID teamId, String sourceKind, int attemptCount,
                                  String failureSqlstate, OffsetDateTime attemptedAt,
                                  OffsetDateTime nextRetryAt, boolean automaticExhausted) { }
