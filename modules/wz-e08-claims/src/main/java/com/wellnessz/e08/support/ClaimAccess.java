package com.wellnessz.e08.support;

import java.util.UUID;
import java.util.function.Predicate;

import org.springframework.stereotype.Component;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.e08.model.Claim;
import com.wellnessz.e08.model.ClaimEnums.ClaimStatus;
import com.wellnessz.e08.model.E08ClaimRepository;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Record-level access to claims. Claims are not cases: the claims team, finance, legal and adjusters have no ABAC access to
 * the OH case, so each endpoint states who may see the claim; anything else is audited and reported as 404 (existence is
 * never revealed – e.g. an adjuster of another organisation, or legal counsel on a claim that is neither litigated nor held).
 */
@Component
public class ClaimAccess {

    private final E08ClaimRepository claims;
    private final AuditService audit;

    public ClaimAccess(E08ClaimRepository claims, AuditService audit) {
        this.claims = claims;
        this.audit = audit;
    }

    public Claim load(UUID id) {
        return claims.findById(id).orElseThrow(() -> ApiException.notFound("Claim"));
    }

    public Claim require(CurrentUser user, UUID id, Predicate<Claim> allowed, String auditAction) {
        Claim c = claims.findById(id).orElse(null);
        if (c == null) {
            throw ApiException.notFound("Claim");
        }
        if (!allowed.test(c)) {
            audit.record(auditAction, "CLAIM", id.toString(), AuditService.Outcome.DENIED, "Not a participant of this claim");
            throw ApiException.notFound("Claim");
        }
        return c;
    }

    // ------------------------------------------------------------------ rules

    /** Legal counsel works on litigated claims and claims under a litigation hold only. */
    public static boolean legalVisible(Claim c) {
        return c.isLitigationHold() || ClaimStatus.LITIGATED.name().equals(c.getStatus());
    }

    public static boolean isAssignedNurse(CurrentUser user, Claim c) {
        return c.getNurseUsername() != null && c.getNurseUsername().equals(user.username());
    }

    /** Adjuster of the payer organisation (and the named adjuster when one is recorded). */
    public static boolean isPayerAdjuster(CurrentUser user, Claim c) {
        String org = ClaimsSupport.partnerOrg(user);
        return user.hasRole(Roles.TPA_ADJUSTER) && org != null && org.equals(c.getPayerCode())
                && (c.getAdjusterUsername() == null || c.getAdjusterUsername().equals(user.username()));
    }

    /** Adjuster of the payer organisation regardless of the named adjuster (claim list / overview of the organisation). */
    public static boolean isPayerOrg(CurrentUser user, Claim c) {
        String org = ClaimsSupport.partnerOrg(user);
        return user.hasRole(Roles.TPA_ADJUSTER) && org != null && org.equals(c.getPayerCode());
    }

    /** GET /claims, /claims/{id}: claims team, nursing head, CMO, case managers / the assigned nurse; legal on litigated / held claims. */
    public static Predicate<Claim> staffReader(CurrentUser user) {
        return c -> user.hasAnyRole(Roles.CLAIMS_COORDINATOR, Roles.NURSING_HEAD, Roles.CMO, Roles.CASE_MANAGER)
                || isAssignedNurse(user, c)
                || (user.hasRole(Roles.LEGAL_COUNSEL) && legalVisible(c));
    }

    /** Shared tasks and messages: claims team, case managers / the assigned nurse, the payer's (assigned) adjuster. */
    public static Predicate<Claim> participant(CurrentUser user) {
        return c -> user.hasAnyRole(Roles.CLAIMS_COORDINATOR, Roles.CASE_MANAGER) || isAssignedNurse(user, c) || isPayerAdjuster(user, c);
    }

    public static Predicate<Claim> roles(CurrentUser user, String... roles) {
        return c -> user.hasAnyRole(roles);
    }
}
