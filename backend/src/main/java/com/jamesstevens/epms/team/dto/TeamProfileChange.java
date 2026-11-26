package com.jamesstevens.epms.team.dto;

/** Profile command data; actor identity is never part of this request. */
public record TeamProfileChange(String name, String description, boolean override, String reason,
                                String reviewedConflicts) {
    public TeamProfileChange(String name, String description, boolean override, String reason) {
        this(name, description, override, reason, null);
    }
}
