package com.wellnessz.e01.referral;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.casekernel.model.CaseCategory;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.error.GlobalExceptionHandler;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.masterdata.EmployeeRepository;
import com.wellnessz.masterdata.JobCodeRepository;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.consent.ConsentPurpose;
import com.wellnessz.security.identity.CurrentUserProvider;

@RestController
@RequestMapping("/api/v1")
public class ReferralController {

    private final ReferralService referrals;
    private final TemplateCatalog templates;
    private final EmployeeRepository employees;
    private final JobCodeRepository jobCodes;
    private final CurrentUserProvider users;
    private final AuditService audit;

    public ReferralController(ReferralService referrals, TemplateCatalog templates, EmployeeRepository employees,
                              JobCodeRepository jobCodes, CurrentUserProvider users, AuditService audit) {
        this.referrals = referrals;
        this.templates = templates;
        this.employees = employees;
        this.jobCodes = jobCodes;
        this.users = users;
        this.audit = audit;
    }

    public record ConsentRequest(boolean assessment, List<ConsentPurpose> purposes) {
    }

    public record SelfReferralRequest(@Pattern(regexp = "[A-Z_]{1,40}") String templateCode, CaseCategory category,
                                      @NotBlank @Size(max = 4000) String description, List<@Pattern(regexp = "[A-Z_]{1,30}") String> bodyParts,
                                      List<@Pattern(regexp = "[A-Z_]{1,30}") String> symptoms, Map<String, Object> answers,
                                      @Size(max = 20) String preferredContact, @Pattern(regexp = "en|ar|fr") String language,
                                      @NotNull ConsentRequest consent) {
    }

    public record ManagerReferralRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9\\-]{1,64}") String employeeId,
                                         @NotBlank @Size(max = 1000) String dutiesAffected, LocalDate startDate,
                                         boolean safetyConcern, @Size(max = 1000) String safetyConcernDetail,
                                         @Size(max = 1000) String adjustmentsTried, @Size(max = 1000) String questionsForOh) {
    }

    public record HrReferralRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9\\-]{1,64}") String employeeId,
                                    @NotBlank @Size(max = 1000) String absenceSummary,
                                    @Pattern(regexp = "INFORMAL|STAGE_1|STAGE_2|FINAL") String policyStage,
                                    Integer absenceDaysLast12Months, @Size(max = 10) List<@Size(max = 1000) String> adviceQuestions) {
    }

    public record EmployeeSummary(String employeeId, String displayName, String departmentCode, String jobCode,
                                  Boolean safetyCritical) {
    }

    @GetMapping("/intake/templates")
    public List<TemplateCatalog.Template> templates(@RequestParam(defaultValue = "EMPLOYEE") TemplateCatalog.Audience audience,
                                                    @RequestParam(defaultValue = "en") @Pattern(regexp = "en|ar|fr") String lang) {
        return templates.templates(audience, lang);
    }

    @GetMapping("/intake/body-map")
    public Map<String, Object> bodyMap() {
        return Map.of("regions", BodyMap.REGIONS);
    }

    @GetMapping("/languages")
    public List<TemplateCatalog.Language> languages() {
        return TemplateCatalog.LANGUAGES;
    }

    @PostMapping("/intake/referrals/self")
    @PreAuthorize("hasRole('EMPLOYEE')")
    @ResponseStatus(HttpStatus.CREATED)
    public ReferralService.ReferralResult self(@Valid @RequestBody SelfReferralRequest r) {
        return referrals.self(users.require(), new ReferralService.SelfReferral(r.templateCode(), r.category(), r.description(),
                r.bodyParts(), r.symptoms(), r.answers(), r.preferredContact(), r.language(),
                new ReferralService.Consent(r.consent().assessment(), r.consent().purposes())));
    }

    @PostMapping("/intake/referrals/manager")
    @PreAuthorize("hasRole('MANAGER')")
    @ResponseStatus(HttpStatus.CREATED)
    public ReferralService.ReferralResult manager(@Valid @RequestBody ManagerReferralRequest r) {
        return referrals.manager(users.require(), new ReferralService.ManagerReferral(r.employeeId(), r.dutiesAffected(),
                r.startDate(), r.safetyConcern(), r.safetyConcernDetail(), r.adjustmentsTried(), r.questionsForOh()));
    }

    @PostMapping("/intake/referrals/hr")
    @PreAuthorize("hasRole('HR')")
    @ResponseStatus(HttpStatus.CREATED)
    public ReferralService.ReferralResult hr(@Valid @RequestBody HrReferralRequest r) {
        return referrals.hr(users.require(), new ReferralService.HrReferral(r.employeeId(), r.absenceSummary(), r.policyStage(),
                r.absenceDaysLast12Months(), r.adviceQuestions()));
    }

    @GetMapping("/intake/employees/lookup")
    @PreAuthorize("hasAnyRole('MANAGER','HR','OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD','CASE_MANAGER','CLINIC_RECEPTION','FIRST_AIDER')")
    public List<EmployeeSummary> lookup(@RequestParam @Size(min = 2, max = 100) String q) {
        var user = users.require();
        String scope = referrals.scopePath(user).orElse(null);
        audit.record("EMPLOYEE_LOOKUP", "EMPLOYEE", null, AuditService.Outcome.SUCCESS, null);
        return employees.search(q.strip(), scope, PageRequest.of(0, 20)).stream().map(this::summary).toList();
    }

    @GetMapping("/intake/employees/by-badge/{badgeId}")
    @PreAuthorize("hasAnyRole('CLINIC_RECEPTION','FIRST_AIDER','OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD','CASE_MANAGER')")
    public EmployeeSummary byBadge(@PathVariable @Pattern(regexp = "[A-Za-z0-9\\-]{1,64}") String badgeId) {
        Employee e = employees.findByBadgeIdAndActiveTrue(badgeId).or(() -> employees.findById(badgeId).filter(Employee::isActive))
                .orElseThrow(() -> ApiException.notFound("Employee"));
        audit.record("EMPLOYEE_BADGE_LOOKUP", "EMPLOYEE", e.getEmployeeId(), AuditService.Outcome.SUCCESS, null);
        return summary(e);
    }

    private EmployeeSummary summary(Employee e) {
        Boolean safety = e.getJobCode() == null ? Boolean.FALSE
                : jobCodes.findById(e.getJobCode()).map(j -> j.getSafetyCategory() != null).orElse(false);
        return new EmployeeSummary(e.getEmployeeId(), e.getDisplayName(), e.getDepartmentCode(), e.getJobCode(), safety);
    }

    @ExceptionHandler(ReferralService.ClinicalContentException.class)
    public ProblemDetail clinical(ReferralService.ClinicalContentException e) {
        ProblemDetail pd = GlobalExceptionHandler.problem(HttpStatus.UNPROCESSABLE_CONTENT, e.code(), e.getMessage());
        pd.setProperty("field", e.field());
        return pd;
    }
}
