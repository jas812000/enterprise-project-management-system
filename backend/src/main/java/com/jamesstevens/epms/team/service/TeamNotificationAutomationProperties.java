package com.jamesstevens.epms.team.service;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Bounded settings for in-app notification processing; no external transport credentials. */
@Validated
@ConfigurationProperties("epms.team.notifications.automatic")
public record TeamNotificationAutomationProperties(
        @DefaultValue("10") @Min(1) @Max(100) int batchSize,
        @DefaultValue("5") @Min(1) @Max(20) int maxAttempts,
        @DefaultValue("30") @Min(1) @Max(86_400) int baseRetrySeconds,
        @DefaultValue("900") @Min(1) @Max(86_400) int maxRetrySeconds,
        @DefaultValue("300") @Min(1) @Max(86_400) int acknowledgementSeconds,
        @DefaultValue("900") @Min(1) @Max(86_400) int repeatEscalationSeconds) { }
