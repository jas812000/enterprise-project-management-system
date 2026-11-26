package com.jamesstevens.epms.team.dto;

import java.util.UUID;

/** Authorized administrative Team projection. */
public record TeamNameConflict(UUID teamId, String name) { }
