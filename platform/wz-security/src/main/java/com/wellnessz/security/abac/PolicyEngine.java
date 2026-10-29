package com.wellnessz.security.abac;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DisclosureLevel;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Attribute-based access control engine. Combining algorithm: deny-overrides, then the most permissive
 * applicable permit wins, otherwise default deny. Every business read of OH data goes through here.
 */
@Component
public class PolicyEngine {

    private final List<AccessPolicy> policies;

    public PolicyEngine(List<AccessPolicy> policies) {
        this.policies = List.copyOf(policies);
    }

    public AccessDecision decide(CurrentUser user, AccessPolicy.Action action, ResourceAttributes resource) {
        for (AccessPolicy p : policies) {
            if (p.isDenyPolicy()) {
                Optional<AccessDecision> d = p.evaluate(user, action, resource);
                if (d.isPresent() && !d.get().permitted()) {
                    return d.get();
                }
            }
        }
        AccessDecision best = null;
        for (AccessPolicy p : policies) {
            if (p.isDenyPolicy()) {
                continue;
            }
            Optional<AccessDecision> d = p.evaluate(user, action, resource);
            if (d.isPresent() && d.get().permitted()
                    && (best == null || d.get().disclosure().ordinal() > best.disclosure().ordinal())) {
                best = d.get();
            }
        }
        return best != null ? best : AccessDecision.deny("No applicable policy (default deny)");
    }

    /** Convenience: throws 403 unless at least {@code minimum} disclosure is granted. */
    public AccessDecision require(CurrentUser user, AccessPolicy.Action action, ResourceAttributes resource,
                                  DisclosureLevel minimum) {
        AccessDecision decision = decide(user, action, resource);
        if (!decision.permitted() || !decision.disclosure().atLeast(minimum)) {
            throw ApiException.forbidden("Access denied");
        }
        return decision;
    }
}
