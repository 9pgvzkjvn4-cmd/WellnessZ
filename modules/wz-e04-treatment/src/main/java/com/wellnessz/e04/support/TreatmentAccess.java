package com.wellnessz.e04.support;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OhCaseRepository;
import com.wellnessz.casekernel.support.CaseAccess;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.common.model.Roles;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Case-level authorisation for E04: the OH team goes through kernel ABAC ({@link CaseAccess}); claims coordinators and
 * finance (no clinical disclosure) may act on the money side of a case – pre-authorisations, invoices – but never on a
 * restricted-class case. Denied access is audited and reported as 404.
 */
@Component
public class TreatmentAccess {

    private final CaseAccess cases;
    private final OhCaseRepository caseRepository;
    private final AuditService audit;

    public TreatmentAccess(CaseAccess cases, OhCaseRepository caseRepository, AuditService audit) {
        this.cases = cases;
        this.caseRepository = caseRepository;
        this.audit = audit;
    }

    public OhCase clinical(CurrentUser user, UUID caseId, AccessPolicy.Action action, String auditAction) {
        return cases.clinical(user, caseId, action, auditAction);
    }

    /** OH team (clinical ABAC) or claims coordinator / finance (financial view, non-restricted cases). */
    public OhCase financial(CurrentUser user, UUID caseId, AccessPolicy.Action action, String auditAction) {
        if (user.hasAnyRole(Roles.OH_TEAM)) {
            return cases.clinical(user, caseId, action, auditAction);
        }
        OhCase c = caseRepository.findById(caseId).orElseThrow(() -> ApiException.notFound("Case"));
        if (!user.hasAnyRole(Roles.CLAIMS_COORDINATOR, Roles.FINANCE) || c.getClassification() == DataClassification.RESTRICTED_CLINICAL) {
            audit.record(auditAction, "CASE", caseId.toString(), AuditService.Outcome.DENIED, "Not permitted for this case");
            throw ApiException.notFound("Case");
        }
        return c;
    }

    /** True when the user may read the case clinically (worklist filtering, no audit entry per row). */
    public boolean canSeeClinical(CurrentUser user, OhCase c) {
        var d = cases.decide(user, c);
        return d.permitted() && d.disclosure().atLeast(com.wellnessz.common.model.DisclosureLevel.CLINICAL);
    }

    public java.util.Optional<OhCase> find(UUID caseId) {
        return caseId == null ? java.util.Optional.empty() : caseRepository.findById(caseId);
    }

    public String caseRef(UUID caseId) {
        return find(caseId).map(OhCase::getCaseRef).orElse(null);
    }
}
