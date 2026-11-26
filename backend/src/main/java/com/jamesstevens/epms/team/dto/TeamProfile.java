package com.jamesstevens.epms.team.dto;

import java.util.UUID;

/** Authorized administrative Team projection. */
public record TeamProfile(UUID teamId, String name, String description,
                          String lifecycleStatus, long version) { }
