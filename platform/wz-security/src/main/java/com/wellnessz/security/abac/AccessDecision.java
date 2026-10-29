package com.wellnessz.security.abac;

import com.wellnessz.common.model.DisclosureLevel;

/** Outcome of a policy evaluation, with the reason recorded in the audit trail. */
public record AccessDecision(boolean permitted, DisclosureLevel disclosure, String reason) {

    public static AccessDecision deny(String reason) {
        return new AccessDecision(false, DisclosureLevel.NONE, reason);
    }

    public static AccessDecision permit(DisclosureLevel level, String reason) {
        return new AccessDecision(true, level, reason);
    }
}
