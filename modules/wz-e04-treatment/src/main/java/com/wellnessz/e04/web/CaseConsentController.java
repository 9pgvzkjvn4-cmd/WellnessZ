package com.wellnessz.e04.web;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Pattern;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.e04.support.Consents;
import com.wellnessz.e04.support.TreatmentAccess;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.consent.ConsentPurpose;
import com.wellnessz.security.identity.CurrentUser;
import com.wellnessz.security.identity.CurrentUserProvider;

/** Active disclosure consents of a case's employee, for the referral wizard and the pre-authorisation form. */
@RestController
@RequestMapping("/api/v1")
public class CaseConsentController {

    private final Consents consents;
    private final TreatmentAccess access;
    private final AuditService audit;
    private final CurrentUserProvider users;

    public CaseConsentController(Consents consents, TreatmentAccess access, AuditService audit, CurrentUserProvider users) {
        this.consents = consents;
        this.access = access;
        this.audit = audit;
        this.users = users;
    }

    @GetMapping("/cases/{caseId}/consents")
    @PreAuthorize(Spel.CLINICIANS_OR_CLAIMS)
    public List<Consents.ConsentView> consents(@PathVariable UUID caseId,
                                               @RequestParam @Pattern(regexp = "TREATMENT_SHARING_PARTNER|INSURER_SHARING") String purpose) {
        CurrentUser user = users.require();
        OhCase c;
        if (user.hasAnyRole(Roles.CLINICIANS)) {
            c = access.clinical(user, caseId, AccessPolicy.Action.READ, "CASE_CONSENTS_READ");
        } else if ("INSURER_SHARING".equals(purpose)) {
            c = access.financial(user, caseId, AccessPolicy.Action.READ, "CASE_CONSENTS_READ");
        } else {
            throw ApiException.forbidden("Claims coordination sees insurer-sharing consents only");
        }
        List<Consents.ConsentView> out = consents.active(c.getEmployeeId(), ConsentPurpose.valueOf(purpose));
        audit.record("CASE_CONSENTS_READ", "CASE", caseId.toString(), AuditService.Outcome.SUCCESS, purpose + " rows=" + out.size());
        return out;
    }
}
