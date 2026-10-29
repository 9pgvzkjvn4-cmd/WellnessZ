package com.wellnessz.security.abac;

import java.util.Optional;

import com.wellnessz.security.identity.CurrentUser;

/**
 * A single ABAC rule. Returning {@link Optional#empty()} means "not applicable".
 * Policies are Spring beans, so business modules (E01–E14) can contribute their own rules.
 */
public interface AccessPolicy {

    Optional<AccessDecision> evaluate(CurrentUser user, Action action, ResourceAttributes resource);

    /** Explicit-deny policies are evaluated first and override any permit (deny-overrides combining). */
    default boolean isDenyPolicy() {
        return false;
    }

    enum Action { READ, CREATE, UPDATE, DELETE, EXPORT }
}
