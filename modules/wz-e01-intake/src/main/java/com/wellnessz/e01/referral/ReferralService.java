package com.wellnessz.e01.referral;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.CaseService;
import com.wellnessz.casekernel.model.CaseCategory;
import com.wellnessz.casekernel.model.CaseSource;
import com.wellnessz.casekernel.model.CaseType;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OpenCaseCommand;
import com.wellnessz.casekernel.module.ModuleRegistry;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.common.text.ClinicalTextDetector;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.masterdata.EmployeeRepository;
import com.wellnessz.masterdata.OrgUnit;
import com.wellnessz.masterdata.OrgUnitRepository;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.consent.ConsentPurpose;
import com.wellnessz.security.consent.ConsentService;
import com.wellnessz.security.identity.CurrentUser;

import tools.jackson.databind.json.JsonMapper;

/**
 * Self, manager and HR referrals (E01-F02). Manager and HR forms are restricted to work-impact facts: any field that
 * looks like a diagnosis or clinical opinion is rejected (E01-BL-007/008), and the referred employee must be inside
 * the referrer's organisational scope.
 */
@Service
public class ReferralService {

    private final CaseService cases;
    private final TemplateCatalog templates;
    private final EmployeeRepository employees;
    private final OrgUnitRepository orgUnits;
    private final ConsentService consents;
    private final ModuleRegistry modules;
    private final AuditService audit;
    private final JsonMapper json;
    private final Clock clock;

    public ReferralService(CaseService cases, TemplateCatalog templates, EmployeeRepository employees, OrgUnitRepository orgUnits,
                           ConsentService consents, ModuleRegistry modules, AuditService audit, JsonMapper json, Clock clock) {
        this.cases = cases;
        this.templates = templates;
        this.employees = employees;
        this.orgUnits = orgUnits;
        this.consents = consents;
        this.modules = modules;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    public record Consent(boolean assessment, List<ConsentPurpose> purposes) {
    }

    public record SelfReferral(String templateCode, CaseCategory category, String description, List<String> bodyParts,
                               List<String> symptoms, Map<String, Object> answers, String preferredContact, String language,
                               Consent consent) {
    }

    public record ManagerReferral(String employeeId, String dutiesAffected, LocalDate startDate, boolean safetyConcern,
                                  String safetyConcernDetail, String adjustmentsTried, String questionsForOh) {
    }

    public record HrReferral(String employeeId, String absenceSummary, String policyStage, Integer absenceDaysLast12Months,
                             List<String> adviceQuestions) {
    }

    public record ReferralResult(String caseId, String caseRef, String acknowledgement) {
    }

    /** Thrown as 422 with the offending field so the UI can highlight it. */
    public static final class ClinicalContentException extends ApiException {
        private static final long serialVersionUID = 1L;
        private final String field;

        public ClinicalContentException(String field) {
            super(HttpStatus.UNPROCESSABLE_CONTENT, "clinical_content_not_allowed",
                    "Field '" + field + "' appears to contain medical information. Describe the impact on work only.");
            this.field = field;
        }

        public String field() {
            return field;
        }
    }

    @Transactional
    public ReferralResult self(CurrentUser user, SelfReferral r) {
        if (user.employeeId() == null || !user.hasRole(Roles.EMPLOYEE)) {
            throw ApiException.forbidden("Self-referral is available to employees only");
        }
        modules.requireEnabled("E13");
        if (r.consent() == null || !r.consent().assessment()) {
            throw ApiException.badRequest("consent_required", "Consent to an occupational health assessment is required");
        }
        String templateCode = r.templateCode() == null ? "CORPORATE" : r.templateCode();
        var template = templates.find(templateCode, r.language())
                .filter(t -> t.audience() == TemplateCatalog.Audience.EMPLOYEE)
                .orElseThrow(() -> ApiException.badRequest("unknown_template", "Unknown intake template"));
        List<String> bodyParts = r.bodyParts() == null ? List.of() : r.bodyParts();
        if (!BodyMap.CODES.containsAll(bodyParts) || bodyParts.size() > 10) {
            throw ApiException.badRequest("invalid_body_part", "Unknown body-map region");
        }
        List<String> symptoms = r.symptoms() == null ? List.of() : r.symptoms();
        if (symptoms.stream().anyMatch(s -> !templates.isSymptom(s)) || symptoms.size() > 20) {
            throw ApiException.badRequest("invalid_symptom", "Unknown symptom code");
        }
        Map<String, Object> answers = sanitiseAnswers(r.answers(), template);
        for (TemplateCatalog.Field f : template.fields()) {
            boolean present = switch (f.type()) {
                case SYMPTOMS -> !symptoms.isEmpty();
                case BODY_MAP -> !bodyParts.isEmpty();
                default -> answers.get(f.key()) != null && !String.valueOf(answers.get(f.key())).isBlank();
            };
            if (f.required() && !present) {
                throw ApiException.badRequest("missing_field", "Required field missing: " + f.key());
            }
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("bodyParts", bodyParts);
        details.put("symptoms", symptoms);
        details.put("answers", answers);
        details.put("preferredContact", r.preferredContact());
        OhCase c = cases.open(OpenCaseCommand.builder(user.employeeId(), r.category() == null ? CaseCategory.OTHER : r.category(),
                        CaseSource.SELF_REFERRAL)
                .employeeName(user.displayName())
                .fallbackOrgUnitCode(lastSegment(user.orgScope()))
                .bodyPart(BodyMap.primary(bodyParts))
                .occurredOn(LocalDate.now(clock))
                .language(lang(r.language()))
                .templateCode(templateCode)
                .clinicalSummary(r.description())
                .intakeDetailsJson(json.writeValueAsString(details))
                .build());
        if (r.consent().purposes() != null) {
            r.consent().purposes().stream().distinct().limit(6).forEach(p -> consents.grant(user, p, "Captured at referral " + c.getCaseRef()));
        }
        audit.record("REFERRAL_SELF", "CASE", c.getId().toString(), AuditService.Outcome.SUCCESS, templateCode);
        return new ReferralResult(c.getId().toString(), c.getCaseRef(),
                "Thank you. Occupational Health has received your referral " + c.getCaseRef() + " and will contact you.");
    }

    @Transactional
    public ReferralResult manager(CurrentUser user, ManagerReferral r) {
        Employee e = inScope(user, r.employeeId());
        guard("dutiesAffected", r.dutiesAffected());
        guard("safetyConcernDetail", r.safetyConcernDetail());
        guard("adjustmentsTried", r.adjustmentsTried());
        guard("questionsForOh", r.questionsForOh());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("referredBy", user.username());
        details.put("dutiesAffected", r.dutiesAffected());
        details.put("startDate", r.startDate() == null ? null : r.startDate().toString());
        details.put("safetyConcern", r.safetyConcern());
        details.put("safetyConcernDetail", r.safetyConcernDetail());
        details.put("adjustmentsTried", r.adjustmentsTried());
        details.put("questionsForOh", r.questionsForOh());
        OhCase c = cases.open(OpenCaseCommand.builder(e.getEmployeeId(), CaseCategory.DECREASE_IN_FITNESS, CaseSource.MANAGER_REFERRAL)
                .caseType(CaseType.FITNESS)
                .occurredOn(r.startDate())
                .templateCode("MANAGER_WORK_IMPACT")
                .urgency(r.safetyConcern() ? com.wellnessz.casekernel.model.Urgency.SEMI_URGENT : null)
                .intakeDetailsJson(json.writeValueAsString(details))
                .build());
        audit.record("REFERRAL_MANAGER", "CASE", c.getId().toString(), AuditService.Outcome.SUCCESS, null);
        return new ReferralResult(c.getId().toString(), c.getCaseRef(), "Referral " + c.getCaseRef() + " sent to Occupational Health.");
    }

    @Transactional
    public ReferralResult hr(CurrentUser user, HrReferral r) {
        Employee e = inScope(user, r.employeeId());
        guard("absenceSummary", r.absenceSummary());
        List<String> questions = r.adviceQuestions() == null ? List.of() : r.adviceQuestions().stream().limit(10).toList();
        for (int i = 0; i < questions.size(); i++) {
            guard("adviceQuestions[" + i + "]", questions.get(i));
        }
        if (r.policyStage() != null && !List.of("INFORMAL", "STAGE_1", "STAGE_2", "FINAL").contains(r.policyStage())) {
            throw ApiException.badRequest("invalid_policy_stage", "Unknown policy stage");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("referredBy", user.username());
        details.put("absenceSummary", r.absenceSummary());
        details.put("policyStage", r.policyStage());
        details.put("absenceDaysLast12Months", r.absenceDaysLast12Months());
        details.put("adviceQuestions", questions);
        OhCase c = cases.open(OpenCaseCommand.builder(e.getEmployeeId(), CaseCategory.OTHER, CaseSource.HR_REFERRAL)
                .caseType(CaseType.ABSENCE)
                .templateCode("HR_ABSENCE")
                .intakeDetailsJson(json.writeValueAsString(details))
                .build());
        audit.record("REFERRAL_HR", "CASE", c.getId().toString(), AuditService.Outcome.SUCCESS, null);
        return new ReferralResult(c.getId().toString(), c.getCaseRef(), "Referral " + c.getCaseRef() + " sent to Occupational Health.");
    }

    /** Employees the referrer may refer / look up: managers and HR within their org scope; OH and reception all. */
    public Optional<String> scopePath(CurrentUser user) {
        if (user.hasAnyRole(Roles.OH_TEAM) || user.hasAnyRole(Roles.CLINIC_RECEPTION, Roles.FIRST_AIDER, Roles.OH_ADMIN)) {
            return Optional.empty();
        }
        if (user.orgScope() == null) {
            throw ApiException.forbidden("No organisational scope assigned");
        }
        return Optional.of(user.orgScope());
    }

    private Employee inScope(CurrentUser user, String employeeId) {
        if (!user.hasAnyRole(Roles.MANAGER, Roles.HR)) {
            throw ApiException.forbidden("Referral form not available for this role");
        }
        Employee e = employees.findById(employeeId == null ? "" : employeeId).filter(Employee::isActive)
                .orElseThrow(() -> ApiException.notFound("Employee"));
        String path = orgUnits.findById(e.getDepartmentCode()).map(OrgUnit::getPath).orElse(null);
        String scope = user.orgScope();
        if (scope == null || path == null || !(path.equals(scope) || path.startsWith(scope + "/"))) {
            audit.record("REFERRAL_SCOPE", "EMPLOYEE", employeeId, AuditService.Outcome.DENIED, "Outside org scope");
            throw ApiException.notFound("Employee");
        }
        return e;
    }

    private static void guard(String field, String value) {
        if (value != null && value.length() > 1000) {
            throw ApiException.badRequest("too_long", "Field '" + field + "' exceeds 1000 characters");
        }
        if (ClinicalTextDetector.looksClinical(value)) {
            throw new ClinicalContentException(field);
        }
    }

    private static Map<String, Object> sanitiseAnswers(Map<String, Object> answers, TemplateCatalog.Template template) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (answers == null) {
            return out;
        }
        for (TemplateCatalog.Field f : template.fields()) {
            Object v = answers.get(f.key());
            if (v == null) {
                continue;
            }
            String s = String.valueOf(v);
            int max = f.maxLength() == null ? 200 : f.maxLength();
            if (s.length() > max) {
                throw ApiException.badRequest("too_long", "Field '" + f.key() + "' is too long");
            }
            if (!f.options().isEmpty() && f.options().stream().noneMatch(o -> o.value().equals(s))) {
                throw ApiException.badRequest("invalid_option", "Invalid value for '" + f.key() + "'");
            }
            out.put(f.key(), s);
        }
        return out;
    }

    private static String lang(String l) {
        return l != null && List.of("en", "ar", "fr").contains(l) ? l : null;
    }

    private static String lastSegment(String path) {
        if (path == null) {
            return null;
        }
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }
}
