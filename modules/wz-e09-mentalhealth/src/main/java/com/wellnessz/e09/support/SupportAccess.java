package com.wellnessz.e09.support;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.e09.model.SupportCase;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.breakglass.BreakGlassService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Access to the restricted class (E09-F01, confidentiality rule 1). Only <b>named</b> mental-health clinicians
 * ({@code PSYCH_CLINICIAN}, limited by {@code mentalhealth.named-clinicians}) read E09 clinical content. Every other attempt on
 * a restricted endpoint is audited DENIED and answered 404 – existence is never revealed. Break-glass (increment 2) opens a
 * single employee's support cases to an OH clinician with an active, DPO-reviewed grant (read only).
 */
@Component
public class SupportAccess {

    public static final String RESOURCE = "SUPPORT_CASE";

    private final MhSupport support;
    private final AuditService audit;
    private final ObjectProvider<BreakGlassService> breakGlass;

    public SupportAccess(MhSupport support, AuditService audit, ObjectProvider<BreakGlassService> breakGlass) {
        this.support = support;
        this.audit = audit;
        this.breakGlass = breakGlass;
    }

    public boolean isNamedClinician(CurrentUser user) {
        if (!user.hasRole(Roles.PSYCH_CLINICIAN)) {
            return false;
        }
        List<String> named = support.listConfig(MhSupport.NAMED_CLINICIANS_KEY, "");
        return named.isEmpty() || named.contains(user.username());
    }

    /** Named clinician or 404 (audited DENIED). */
    public void requireNamed(CurrentUser user, String auditAction, String resourceId) {
        if (!isNamedClinician(user)) {
            throw deny(auditAction, resourceId, "Not a named mental-health clinician");
        }
    }

    /** Named clinician or one of the extra roles (e.g. AME for relief), else 404 (audited DENIED). */
    public void requireNamedOr(CurrentUser user, String auditAction, String resourceId, String... roles) {
        if (!isNamedClinician(user) && !user.hasAnyRole(roles)) {
            throw deny(auditAction, resourceId, "Restricted class");
        }
    }

    /** One of the roles, else 404 (audited DENIED). */
    public void requireRoles(CurrentUser user, String auditAction, String resourceId, String... roles) {
        if (!user.hasAnyRole(roles)) {
            throw deny(auditAction, resourceId, "Restricted class");
        }
    }

    /** Read access to one support case: named clinician, or break-glass on that employee. */
    public boolean canRead(CurrentUser user, SupportCase c) {
        return isNamedClinician(user) || hasBreakGlass(user, c.getEmployeeId());
    }

    public boolean hasBreakGlass(CurrentUser user, String employeeId) {
        if (!user.hasAnyRole(Roles.OH_CLINICIAN, Roles.OH_PHYSICIAN, Roles.AME, Roles.CMO, Roles.NURSING_HEAD)) {
            return false;
        }
        BreakGlassService s = breakGlass.getIfAvailable();
        return s != null && s.hasActiveGrant(user.username(), employeeId);
    }

    public ApiException deny(String auditAction, String resourceId, String reason) {
        audit.record(auditAction, RESOURCE, resourceId, AuditService.Outcome.DENIED, reason);
        return ApiException.notFound("Support case");
    }
}
