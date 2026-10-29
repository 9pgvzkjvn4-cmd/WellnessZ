package com.wellnessz.security.audit;

import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authorization.event.AuthorizationDeniedEvent;
import org.springframework.stereotype.Component;

/** Audits authentication failures and authorisation denials raised by Spring Security. */
@Component
public class SecurityEventAuditor {

    private final AuditService auditService;

    public SecurityEventAuditor(AuditService auditService) {
        this.auditService = auditService;
    }

    @EventListener
    public void onAuthenticationFailure(AbstractAuthenticationFailureEvent event) {
        auditService.recordAs("anonymous", "", "AUTHENTICATION", null, null, AuditService.Outcome.FAILURE,
                event.getException().getClass().getSimpleName());
    }

    @EventListener
    public void onAuthorizationDenied(AuthorizationDeniedEvent<?> event) {
        auditService.record("AUTHORIZATION", null, null, AuditService.Outcome.DENIED, "Access denied by RBAC rule");
    }
}
