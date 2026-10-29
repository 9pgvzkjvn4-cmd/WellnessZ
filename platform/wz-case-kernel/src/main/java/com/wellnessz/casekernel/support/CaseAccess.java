package com.wellnessz.casekernel.support;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OhCaseRepository;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DisclosureLevel;
import com.wellnessz.security.abac.AccessDecision;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.abac.PolicyEngine;
import com.wellnessz.security.abac.ResourceAttributes;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/** Single place where modules load a case under ABAC; denied access is audited and reported as 404. */
@Component
public class CaseAccess {

    private final OhCaseRepository cases;
    private final PolicyEngine policy;
    private final AuditService audit;

    public CaseAccess(OhCaseRepository cases, PolicyEngine policy, AuditService audit) {
        this.cases = cases;
        this.policy = policy;
        this.audit = audit;
    }

    public static ResourceAttributes attributes(OhCase c) {
        return new ResourceAttributes("CASE", c.getEmployeeId(), c.getOrgUnitPath(), c.getClassification());
    }

    /** Loads the case requiring at least {@code minimum} disclosure for {@code action}. */
    public OhCase require(CurrentUser user, UUID id, AccessPolicy.Action action, DisclosureLevel minimum, String auditAction) {
        OhCase c = cases.findById(id).orElseThrow(() -> ApiException.notFound("Case"));
        AccessDecision d = policy.decide(user, action, attributes(c));
        if (!d.permitted() || !d.disclosure().atLeast(minimum)) {
            audit.record(auditAction, "CASE", id.toString(), AuditService.Outcome.DENIED, d.reason());
            throw ApiException.notFound("Case");
        }
        return c;
    }

    /** Clinical read/write by the OH team. */
    public OhCase clinical(CurrentUser user, UUID id, AccessPolicy.Action action, String auditAction) {
        return require(user, id, action, DisclosureLevel.CLINICAL, auditAction);
    }

    public AccessDecision decide(CurrentUser user, OhCase c) {
        return policy.decide(user, AccessPolicy.Action.READ, attributes(c));
    }
}
