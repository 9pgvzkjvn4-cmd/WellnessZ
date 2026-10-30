package com.wellnessz.security.web;

import java.util.Set;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.common.model.DisclosureLevel;
import com.wellnessz.common.model.Roles;
import com.wellnessz.security.identity.CurrentUser;
import com.wellnessz.security.identity.CurrentUserProvider;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final CurrentUserProvider users;

    public MeController(CurrentUserProvider users) {
        this.users = users;
    }

    public record MeResponse(String subject, String username, String displayName, String employeeId, String orgScope,
                             Set<String> roles, DisclosureLevel maxDisclosure) {
    }

    @GetMapping
    public MeResponse me() {
        CurrentUser u = users.require();
        DisclosureLevel level = u.hasAnyRole(Roles.PSYCH_CLINICIAN, Roles.MRO) ? DisclosureLevel.RESTRICTED_CLINICAL
                : u.hasAnyRole(Roles.OH_CLINICIAN, Roles.CASE_MANAGER, Roles.OH_PHYSICIAN, Roles.AME, Roles.CMO,
                        Roles.NURSING_HEAD) ? DisclosureLevel.CLINICAL
                : u.hasAnyRole(Roles.MANAGER, Roles.HR, Roles.HSE_OFFICER, Roles.CREW_SCHEDULING, Roles.HYGIENE_MANAGER,
                        Roles.CREW_LICENSING, Roles.CLINIC_SCHEDULER, Roles.CLAIMS_COORDINATOR, Roles.TPA_ADJUSTER, Roles.LEGAL_COUNSEL,
                        Roles.FINANCE, Roles.PROCUREMENT, Roles.PARTNER_PROVIDER, Roles.PEER_SUPPORT_COORDINATOR, Roles.SAFETY_MANAGER,
                        Roles.DA_PROGRAMME_MANAGER, Roles.COLLECTOR, Roles.HEALTH_PROMOTION_LEAD, Roles.ERGONOMIST, Roles.PEER_SUPPORT_VOLUNTEER)
                        ? DisclosureLevel.STATUS_ONLY
                : u.hasAnyRole(Roles.EMPLOYEE, Roles.CANDIDATE) ? DisclosureLevel.SELF : DisclosureLevel.NONE;
        return new MeResponse(u.subject(), u.username(), u.displayName(), u.employeeId(), u.orgScope(), u.roles(), level);
    }
}
