package com.wellnessz.e09.fitness;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.CaseService;
import com.wellnessz.casekernel.FitnessFeed;
import com.wellnessz.casekernel.model.CaseTask;
import com.wellnessz.casekernel.model.CaseTaskRepository;
import com.wellnessz.casekernel.model.FitnessStatus;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.common.model.Roles;
import com.wellnessz.documents.DocumentService;
import com.wellnessz.e09.casework.SupportCaseService;
import com.wellnessz.e09.model.E09MhFitnessAssessmentRepository;
import com.wellnessz.e09.model.MentalHealthEnums.Concern;
import com.wellnessz.e09.model.MentalHealthEnums.FitnessDecision;
import com.wellnessz.e09.model.MentalHealthEnums.Source;
import com.wellnessz.e09.model.MhFitnessAssessment;
import com.wellnessz.e09.model.SupportCase;
import com.wellnessz.e09.support.MhSupport;
import com.wellnessz.e09.support.SupportAccess;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.masterdata.fitness.FitnessStandard;
import com.wellnessz.masterdata.fitness.FitnessStandardRepository;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

import tools.jackson.core.type.TypeReference;

/**
 * Safety-critical mental-health fitness assessment (E09-F06 / E09-BL-013): structured assessment against a fitness standard with
 * medication review, decision, review schedule (kernel FOLLOW_UP task) and – where mandated – the restricted
 * {@code AUTHORITY_FITNESS_REPORT}. Rostering / HR receive the status only, through the kernel feed.
 */
@Service
public class MhFitnessService {

    private static final TypeReference<List<Medication>> MEDICATIONS = new TypeReference<>() {
    };

    private final E09MhFitnessAssessmentRepository assessments;
    private final SupportCaseService cases;
    private final CaseService kernel;
    private final CaseTaskRepository tasks;
    private final FitnessStandardRepository standards;
    private final DocumentService documents;
    private final SupportAccess access;
    private final MhSupport support;
    private final AuditService audit;

    public MhFitnessService(E09MhFitnessAssessmentRepository assessments, SupportCaseService cases, CaseService kernel, CaseTaskRepository tasks,
                            FitnessStandardRepository standards, DocumentService documents, SupportAccess access, MhSupport support,
                            AuditService audit) {
        this.assessments = assessments;
        this.cases = cases;
        this.kernel = kernel;
        this.tasks = tasks;
        this.standards = standards;
        this.documents = documents;
        this.access = access;
        this.support = support;
        this.audit = audit;
    }

    public record Medication(String name, String drugClass, String dose, LocalDate startDate) {
    }

    public record CreateCommand(String employeeId, UUID supportCaseId, String standardCode, List<Medication> medications, String findings,
                                FitnessDecision decision, String limitations, LocalDate reviewDate, boolean authorityReportRequired) {
    }

    public record AssessmentView(UUID id, String employeeId, String displayName, UUID supportCaseId, String supportCaseRef, String standardCode,
                                 String standardTitle, List<Medication> medications, String findings, String decision, String limitations,
                                 LocalDate reviewDate, boolean authorityReportRequired, UUID documentId, String documentNumber,
                                 String fitnessStatus, String rosterStatus, UUID reviewTaskId, String assessedBy, Instant assessedAt) {
    }

    @Transactional
    public AssessmentView create(CurrentUser user, CreateCommand cmd) {
        access.requireRoles(user, "MH_FITNESS_ASSESS", cmd.employeeId(), Roles.AME, Roles.OH_PHYSICIAN);
        Employee e = support.employee(cmd.employeeId()).orElseThrow(() -> ApiException.notFound("Employee"));
        FitnessStandard standard = standards.current(cmd.standardCode())
                .orElseThrow(() -> MhSupport.unprocessable("unknown_standard", "Unknown or inactive fitness standard"));
        String limitations = MhSupport.screened("limitations", cmd.limitations(), 1000);
        if (cmd.decision() == FitnessDecision.FIT_WITH_LIMITATIONS && limitations == null) {
            throw MhSupport.unprocessable("limitations_required", "Fit with limitations needs the limitations");
        }
        if (cmd.reviewDate().isBefore(support.today())) {
            throw MhSupport.unprocessable("invalid_review_date", "The review date lies in the past");
        }
        SupportCase c;
        if (cmd.supportCaseId() != null) {
            c = cases.load(cmd.supportCaseId());
            if (!c.getEmployeeId().equals(e.getEmployeeId())) {
                throw ApiException.notFound("Support case");
            }
        } else {
            c = cases.openOrCreate(e.getEmployeeId(), Source.CLINICIAN, Concern.OTHER, user.username());
        }
        OhCase k = cases.kernelCase(c).orElseThrow(() -> ApiException.notFound("Case"));
        Instant now = support.now();
        FitnessStatus status = switch (cmd.decision()) {
            case FIT -> FitnessStatus.FIT;
            case FIT_WITH_LIMITATIONS -> FitnessStatus.FIT_WITH_RESTRICTIONS;
            case TEMPORARILY_UNFIT -> FitnessStatus.TEMPORARILY_UNFIT;
            case REFER_TO_AUTHORITY -> FitnessStatus.UNDER_REVIEW;
        };
        LocalDate until = status == FitnessStatus.FIT ? null : cmd.reviewDate();
        k.recordFitness(status, until, now);
        kernel.publishFitness(k, new FitnessFeed(support.today(), until, cmd.reviewDate(), List.of()));
        CaseTask task = tasks.save(new CaseTask(k.getId(), CaseTask.Type.FOLLOW_UP, "Mental-health fitness review (" + standard.getCode() + ")",
                cmd.reviewDate().atTime(9, 0).toInstant(ZoneOffset.UTC), user.username(), user.username(), now));

        MhFitnessAssessment a = MhFitnessAssessment.create();
        a.setEmployeeId(e.getEmployeeId());
        a.setSupportCaseId(c.getId());
        a.setKernelCaseId(k.getId());
        a.setStandardCode(standard.getCode());
        a.setMedications(cmd.medications() == null || cmd.medications().isEmpty() ? null : support.json(cmd.medications()));
        a.setFindings(MhSupport.trimmed("findings", cmd.findings(), 8000));
        a.setDecision(cmd.decision().name());
        a.setLimitations(limitations);
        a.setReviewDate(cmd.reviewDate());
        a.setAuthorityReportRequired(cmd.authorityReportRequired() || cmd.decision() == FitnessDecision.REFER_TO_AUTHORITY);
        a.setReviewTaskId(task.getId());
        a.setAssessedBy(user.username());
        a.setAssessedAt(now);
        String documentNumber = null;
        if (a.isAuthorityReportRequired()) {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("employeeName", e.getDisplayName());
            fields.put("employeeId", e.getEmployeeId());
            fields.put("licence", standard.getTitle());
            fields.put("assessmentDate", support.today().toString());
            fields.put("decision", label(cmd.decision()));
            if (limitations != null) {
                fields.put("limitations", limitations);
            }
            fields.put("reviewDate", cmd.reviewDate().toString());
            fields.put("examiner", user.displayName() == null ? user.username() : user.displayName());
            DocumentService.DocumentView doc = documents.generateSigned(user.username(), "AUTHORITY_FITNESS_REPORT", e.getEmployeeId(), k.getId(),
                    fields, DataClassification.RESTRICTED_CLINICAL);
            a.setDocumentId(doc.id());
            documentNumber = doc.documentNumber();
        }
        assessments.save(a);
        c.setUpdatedAt(now);
        cases.event(c, "FITNESS_ASSESSED", "Safety-critical fitness assessment: " + cmd.decision() + ", review " + cmd.reviewDate(), user.username());
        audit.record("MH_FITNESS_ASSESS", SupportAccess.RESOURCE, c.getId().toString(), AuditService.Outcome.SUCCESS, cmd.decision().name());
        return view(a, c, standard.getTitle(), documentNumber, status);
    }

    @Transactional(readOnly = true)
    public List<AssessmentView> history(CurrentUser user, String employeeId) {
        access.requireNamedOr(user, "MH_FITNESS_HISTORY", employeeId, Roles.AME, Roles.OH_PHYSICIAN);
        List<AssessmentView> out = assessments.findByEmployeeIdOrderByAssessedAtDesc(employeeId).stream().map(a -> {
            SupportCase c = cases.load(a.getSupportCaseId());
            String title = standards.findFirstByCodeOrderByVersionDesc(a.getStandardCode()).map(FitnessStandard::getTitle).orElse(a.getStandardCode());
            FitnessStatus status = cases.kernelCase(c).map(OhCase::getFitnessStatus).orElse(null);
            return view(a, c, title, null, status);
        }).toList();
        audit.record("MH_FITNESS_HISTORY", "EMPLOYEE", employeeId, AuditService.Outcome.SUCCESS, "returned=" + out.size());
        return out;
    }

    private AssessmentView view(MhFitnessAssessment a, SupportCase c, String standardTitle, String documentNumber, FitnessStatus status) {
        List<Medication> meds = a.getMedications() == null ? List.of() : support.read(a.getMedications(), MEDICATIONS);
        return new AssessmentView(a.getId(), a.getEmployeeId(), support.name(a.getEmployeeId()), c.getId(), c.getCaseRef(), a.getStandardCode(),
                standardTitle, meds, a.getFindings(), a.getDecision(), a.getLimitations(), a.getReviewDate(), a.isAuthorityReportRequired(),
                a.getDocumentId(), documentNumber, status == null ? null : status.name(), status == null ? null : FitnessFeed.rosterStatus(status),
                a.getReviewTaskId(), a.getAssessedBy(), a.getAssessedAt());
    }

    private static String label(FitnessDecision d) {
        return switch (d) {
            case FIT -> "Fit";
            case FIT_WITH_LIMITATIONS -> "Fit with limitations";
            case TEMPORARILY_UNFIT -> "Temporarily unfit";
            case REFER_TO_AUTHORITY -> "Referred to the authority";
        };
    }
}
