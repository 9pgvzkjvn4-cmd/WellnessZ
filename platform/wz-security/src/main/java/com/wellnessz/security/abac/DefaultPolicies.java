package com.wellnessz.security.abac;

import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.wellnessz.common.model.DataClassification;
import com.wellnessz.common.model.DisclosureLevel;
import com.wellnessz.common.model.Roles;
import com.wellnessz.security.breakglass.BreakGlassService;

/**
 * Baseline OH disclosure rules from the logical architecture:
 * clinical data stays in the OH domain; operations and HR receive status-only outputs;
 * psychological health (E09) and similar data form a restricted class.
 */
@Configuration
public class DefaultPolicies {

    /** Restricted class: only psychological-health clinicians (and the data subject) may see restricted clinical data. */
    @Bean
    AccessPolicy restrictedClassPolicy(ObjectProvider<BreakGlassService> breakGlass) {
        return new AccessPolicy() {
            @Override
            public Optional<AccessDecision> evaluate(com.wellnessz.security.identity.CurrentUser user, Action action,
                                                     ResourceAttributes r) {
                if (r.classification() == DataClassification.RESTRICTED_CLINICAL
                        && !user.hasRole(Roles.PSYCH_CLINICIAN)
                        && !isSelf(user, r)
                        && !user.hasAnyRole(Roles.MANAGER, Roles.HR) // managers/HR still get status only
                        && !hasBreakGlass(breakGlass, user, r)) {
                    return Optional.of(AccessDecision.deny("Restricted clinical class"));
                }
                return Optional.empty();
            }

            @Override
            public boolean isDenyPolicy() {
                return true;
            }
        };
    }

    @Bean
    AccessPolicy clinicianPolicy() {
        return (user, action, r) -> {
            if (user.hasRole(Roles.PSYCH_CLINICIAN)) {
                return Optional.of(AccessDecision.permit(DisclosureLevel.RESTRICTED_CLINICAL, "Psychological health clinician"));
            }
            if (user.hasAnyRole(Roles.OH_CLINICIAN, Roles.CASE_MANAGER, Roles.OH_PHYSICIAN, Roles.AME, Roles.CMO,
                    Roles.NURSING_HEAD)
                    && r.classification() != DataClassification.RESTRICTED_CLINICAL) {
                return Optional.of(AccessDecision.permit(DisclosureLevel.CLINICAL, "OH clinical role"));
            }
            return Optional.empty();
        };
    }

    /** Break-glass (E03-BL-021): an active, justified and DPO-reviewed emergency grant opens the restricted class. */
    @Bean
    AccessPolicy breakGlassPolicy(ObjectProvider<BreakGlassService> breakGlass) {
        return (user, action, r) -> r.classification() == DataClassification.RESTRICTED_CLINICAL && hasBreakGlass(breakGlass, user, r)
                ? Optional.of(AccessDecision.permit(DisclosureLevel.RESTRICTED_CLINICAL, "Break-glass emergency access"))
                : Optional.empty();
    }

    static boolean hasBreakGlass(ObjectProvider<BreakGlassService> breakGlass, com.wellnessz.security.identity.CurrentUser user,
                                 ResourceAttributes r) {
        if (!user.hasAnyRole(Roles.OH_CLINICIAN, Roles.OH_PHYSICIAN, Roles.AME, Roles.CMO, Roles.NURSING_HEAD)) {
            return false;
        }
        BreakGlassService service = breakGlass.getIfAvailable();
        return service != null && service.hasActiveGrant(user.username(), r.ownerEmployeeId());
    }

    /** Data subject sees their own record (right of access). */
    @Bean
    AccessPolicy selfAccessPolicy() {
        return (user, action, r) -> isSelf(user, r) && action == AccessPolicy.Action.READ
                ? Optional.of(AccessDecision.permit(DisclosureLevel.SELF, "Data subject"))
                : Optional.empty();
    }

    /** Line managers / HR: status-only, and only for employees inside their org scope. */
    @Bean
    AccessPolicy statusOnlyPolicy() {
        return (user, action, r) -> {
            if (action != AccessPolicy.Action.READ || !user.hasAnyRole(Roles.MANAGER, Roles.HR)) {
                return Optional.empty();
            }
            String scope = user.orgScope();
            if (scope != null && r.orgUnitPath() != null
                    && (r.orgUnitPath().equals(scope) || r.orgUnitPath().startsWith(scope.endsWith("/") ? scope : scope + "/"))) {
                return Optional.of(AccessDecision.permit(DisclosureLevel.STATUS_ONLY, "Within org scope"));
            }
            return Optional.of(AccessDecision.deny("Outside org scope"));
        };
    }

    static boolean isSelf(com.wellnessz.security.identity.CurrentUser user, ResourceAttributes r) {
        return user.employeeId() != null && user.employeeId().equals(r.ownerEmployeeId());
    }
}
