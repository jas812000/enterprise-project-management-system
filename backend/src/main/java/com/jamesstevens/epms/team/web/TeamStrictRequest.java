package com.jamesstevens.epms.team.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;

/** Reject undeclared JSON properties on Team commands without echoing untrusted input. */
public interface TeamStrictRequest {
    @JsonAnySetter
    default void rejectUnknownProperty(String propertyName, Object value) {
        throw new IllegalArgumentException("Unknown Team request property.");
    }
}
