package com.jamesstevens.epms.team.dto;

/** Administrative readiness warning without alternate identity or confidential detail. */
public record TeamAlternateReadiness(int priority, String readiness) { }
