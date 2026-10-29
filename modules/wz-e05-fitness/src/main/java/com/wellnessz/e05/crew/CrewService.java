package com.wellnessz.e05.crew;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.documents.DocumentService;
import com.wellnessz.e05.decision.FitnessDecisionService;
import com.wellnessz.e05.decision.LimitationCodes;
import com.wellnessz.e05.support.FitnessSupport;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.masterdata.fitness.FitnessStandard;
import com.wellnessz.masterdata.fitness.FitnessStandardRepository;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/** Crew fitness (E05-F04): EASA limitation-code catalogue and AME crew assessments with CAA-layout medical reports. */
@Service
public class CrewService {

    private final FitnessStandardRepository standards;
    private final FitnessDecisionService decisions;
    private final DocumentService documents;
    private final FitnessSupport support;
    private final AuditService audit;

    public CrewService(FitnessStandardRepository standards, FitnessDecisionService decisions, DocumentService documents,
                       FitnessSupport support, AuditService audit) {
        this.standards = standards;
        this.decisions = decisions;
        this.documents = documents;
        this.support = support;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ limitation codes

    public record LimitationCode(String code, String label, List<String> appliesTo, boolean operational) {
    }

    @Transactional(readOnly = true)
    public List<LimitationCode> limitationCodes() {
        List<FitnessStandard> active = standards.findByStatusOrderByCodeAsc("ACTIVE");
        List<LimitationCode> out = new ArrayList<>();
        LimitationCodes.labels().forEach((code, label) -> out.add(new LimitationCode(code, label,
                active.stream().filter(s -> s.limitationCodeList().contains(code)).map(FitnessStandard::getCode).toList(),
                LimitationCodes.isOperational(code))));
        return out;
    }

    // ------------------------------------------------------------------ crew assessment (E05-BL-011)

    public record CrewAssessment(String standardCode, String outcome, List<String> limitationCodes, LocalDate validFrom,
                                 LocalDate validUntil, LocalDate reviewDate, LocalDate examinationDate, String rationale) {
    }

    @Transactional
    public FitnessDecisionService.DecisionView assess(CurrentUser user, UUID caseId, CrewAssessment a) {
        if (!user.hasAnyRole(Roles.AME, Roles.CMO)) {
            throw ApiException.forbidden("Crew assessments are recorded by an AME or the CMO");
        }
        if (!List.of("CAA_CLASS1", "CAA_CLASS2", "CABIN_CREW").contains(a.standardCode())) {
            throw ApiException.badRequest("invalid_standard", "Crew assessments use CAA_CLASS1, CAA_CLASS2 or CABIN_CREW");
        }
        LocalDate examined = a.examinationDate() == null ? support.today() : a.examinationDate();
        if (examined.isAfter(support.today())) {
            throw ApiException.badRequest("invalid_examination_date", "The examination date cannot be in the future");
        }
        FitnessDecisionService.DecisionView d = decisions.decide(user, caseId, new FitnessDecisionService.DecisionCommand(a.standardCode(),
                a.outcome(), a.limitationCodes(), List.of(), a.validFrom(), a.validUntil(), a.reviewDate(), a.rationale(), null, null,
                Boolean.FALSE, examined, true));
        boolean pilot = !"CABIN_CREW".equals(a.standardCode());
        Employee e = support.employee(d.employeeId()).orElse(null);
        Map<String, String> f = new LinkedHashMap<>();
        f.put("employeeName", e == null ? d.employeeId() : e.getDisplayName());
        f.put("employeeId", d.employeeId());
        if (pilot) {
            f.put("medicalClass", "CAA_CLASS1".equals(a.standardCode()) ? "Class 1" : "Class 2");
        }
        f.put("examinationDate", examined.toString());
        f.put("assessment", FitnessDecisionService.outcomeLabel(d.outcome())
                + ("AWAITING_SIGNOFF".equals(d.status()) ? " (subject to CMO sign-off)" : ""));
        if (!d.limitationCodes().isEmpty()) {
            f.put("limitations", String.join("; ", d.limitationCodes().stream()
                    .map(c -> c + " – " + LimitationCodes.labels().get(c)).toList()));
        }
        f.put("validUntil", d.validUntil() == null ? "Not applicable" : d.validUntil().toString());
        f.put("ameName", support.staffName(user.username()));
        DocumentService.DocumentView report = documents.generate(user, pilot ? "CREW_MEDICAL_REPORT" : "CABIN_CREW_MEDICAL_REPORT",
                d.employeeId(), caseId, f);
        documents.sign(user, report.id());
        decisions.shareWithEmployee(user, report.id());
        decisions.attachReport(d.id(), report.id());
        audit.record("CREW_ASSESSMENT", "CASE", caseId.toString(), AuditService.Outcome.SUCCESS, a.standardCode() + " " + a.outcome());
        return new FitnessDecisionService.DecisionView(d.id(), d.caseId(), d.caseRef(), d.employeeId(), d.standardCode(), d.standardTitle(),
                d.standardVersion(), d.outcome(), d.kernelStatus(), d.limitationCodes(), d.restrictions(), d.validFrom(), d.validUntil(),
                d.reviewDate(), d.rationale(), d.durationOverrideReason(), d.status(), d.requiredSignoffRole(), d.decidedBy(), d.decidedAt(),
                d.signoffBy(), d.signoffAt(), d.signoffNote(), d.certificateDocumentId(), true, examined, report.id());
    }
}
