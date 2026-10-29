package com.wellnessz.security.disclosure;

import java.util.List;

import jakarta.validation.constraints.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;
import com.wellnessz.security.identity.CurrentUserProvider;

@RestController
@RequestMapping("/api/v1")
public class DisclosureController {

    private final DisclosureLogService service;
    private final CurrentUserProvider users;
    private final AuditService audit;

    public DisclosureController(DisclosureLogService service, CurrentUserProvider users, AuditService audit) {
        this.service = service;
        this.users = users;
        this.audit = audit;
    }

    @GetMapping("/me/disclosures")
    public List<DisclosureLogService.Disclosure> mine() {
        CurrentUser user = users.require();
        if (user.employeeId() == null) {
            throw ApiException.forbidden("No employee identity on this account");
        }
        return service.forEmployee(user.employeeId(), false);
    }

    @GetMapping("/disclosures")
    @PreAuthorize("hasAnyRole('DPO','OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD','CASE_MANAGER','PSYCH_CLINICIAN')")
    public List<DisclosureLogService.Disclosure> forEmployee(@RequestParam @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String employeeId) {
        audit.record("DISCLOSURE_LOG_READ", "EMPLOYEE", employeeId, AuditService.Outcome.SUCCESS, null);
        return service.forEmployee(employeeId, true);
    }
}
