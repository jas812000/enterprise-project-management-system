package com.jamesstevens.epms.team.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Authorized metadata only; confidential reasons are never included. */
public record TeamNotification(UUID notificationId, UUID teamId, String kind, String severity,
                               OffsetDateTime occurredAt, OffsetDateTime readAt, OffsetDateTime acknowledgedAt) { }
