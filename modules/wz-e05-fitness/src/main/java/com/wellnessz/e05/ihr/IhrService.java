package com.wellnessz.e05.ihr;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.support.CaseAccess;
import com.wellnessz.casekernel.support.CaseTimeline;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DisclosureLevel;
import com.wellnessz.common.model.Roles;
import com.wellnessz.documents.DocumentService;
import com.wellnessz.documents.model.DocumentShare;
import com.wellnessz.e05.model.IhrAssessment;
import com.wellnessz.e05.model.IhrAssessmentRepository;
import com.wellnessz.e05.support.FitnessSupport;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Ill-health retirement / redeployment (E05-F07, E05-BL-018): physician assessment (findings OH-only), CMO decision with
 * a decision letter and a 28-day appeal window, appeal with optional second opinion, and an appeal decision due within
 * 30 days taken by a CMO who was neither the assessing physician nor the original decision maker. HR sees type, status,
 * functional decision text and dates only.
 */
@Service
public class IhrService {

    static final Set<String> TYPES = Set.of("ILL_HEALTH_RETIREMENT", "REDEPLOYMENT");

    private final IhrAssessmentRepository assessments;
    private final CaseAccess access;
    private final CaseTimeline timeline;
    private final DocumentService documents;
    private final FitnessSupport support;
    private final AuditService audit;
    private final int appealWindowDays;
    private final int appealDecisionDays;

    public IhrService(IhrAssessmentRepository assessments, CaseAccess access, CaseTimeline timeline, DocumentService documents,
                      FitnessSupport support, AuditService audit,
                      @Value("${wellnessz.fitness.ihr-appeal-window-days:28}") int appealWindowDays,
                      @Value("${wellnessz.fitness.ihr-appeal-decision-days:30}") int appealDecisionDays) {
        this.assessments = assessments;
        this.access = access;
        this.timeline = timeline;
        this.documents = documents;
        this.support = support;
        this.audit = audit;
        this.appealWindowDays = appealWindowDays;
        this.appealDecisionDays = appealDecisionDays;
    }

    /** OH view (all fields). */
    public record IhrView(UUID id, UUID caseId, String caseRef, String employeeId, String type, String findings, String recommendation,
                          String policyReference, String status, String assessedBy, Instant assessedAt, Boolean approved, String decisionText,
                          String cmoNote, String decidedBy, Instant decidedAt, LocalDate appealDeadline, UUID letterDocumentId,
                          String appealGrounds, String secondOpinionBy, String appealedBy, Instant appealedAt, LocalDate appealDueBy,
                          Boolean appealUpheld, String appealDecisionText, String appealDecidedBy, Instant appealDecidedAt,
                          UUID appealLetterDocumentId) {
    }

    /** HR view: type, status, functional decision text and dates only. */
    public record IhrHrView(UUID id, String type, String status, String decisionText, Instant assessedAt, Instant decidedAt,
                            LocalDate appealDeadline, Instant appealedAt, LocalDate appealDueBy, String appealDecisionText, Instant appealDecidedAt) {
    }

    @Transactional
    public IhrView assess(CurrentUser user, UUID caseId, String type, String findings, String recommendation, String policyReference) {
        if (!user.hasAnyRole(Roles.PHYSICIANS)) {
            throw ApiException.forbidden("Ill-health retirement / redeployment assessments are made by physicians");
        }
        OhCase c = access.clinical(user, caseId, AccessPolicy.Action.UPDATE, "IHR_ASSESSMENT");
        if (!TYPES.contains(type)) {
            throw ApiException.badRequest("invalid_type", "type must be ILL_HEALTH_RETIREMENT or REDEPLOYMENT");
        }
        String f = FitnessSupport.clinical("findings", findings, 8000);
        String r = FitnessSupport.clinical("recommendation", recommendation, 4000);
        if (f == null || r == null) {
            throw ApiException.badRequest("missing_field", "findings and recommendation are required");
        }
        IhrAssessment a = IhrAssessment.create();
        a.setCaseId(c.getId());
        a.setCaseRef(c.getCaseRef());
        a.setEmployeeId(c.getEmployeeId());
        a.setAssessmentType(type);
        a.setFindings(f);
        a.setRecommendation(r);
        a.setPolicyReference(FitnessSupport.functional("policyReference", policyReference, 200));
        a.setStatus("AWAITING_CMO");
        a.setAssessedBy(user.username());
        a.setAssessedAt(support.clock().instant());
        assessments.save(a);
        support.notifyRole(Roles.CMO, "fitness.signoff-required", Map.of("caseRef", c.getCaseRef(), "level", "CMO"));
        timeline.record(c.getId(), "IHR", label(type) + " assessment submitted for CMO decision");
        audit.record("IHR_ASSESSMENT", "CASE", caseId.toString(), AuditService.Outcome.SUCCESS, type);
        return view(a);
    }

    @Transactional
    public IhrView cmoDecision(CurrentUser user, UUID id, boolean approve, String decisionText, String note) {
        requireCmo(user);
        IhrAssessment a = load(user, id, "IHR_CMO_DECISION");
        if (!"AWAITING_CMO".equals(a.getStatus())) {
            throw ApiException.conflict("invalid_state", "The assessment is not awaiting a CMO decision");
        }
        String text = FitnessSupport.functional("decisionText", decisionText, 4000);
        if (text == null) {
            throw ApiException.badRequest("decision_text_required", "decisionText is required");
        }
        Instant now = support.clock().instant();
        LocalDate today = support.today();
        a.setApproved(approve);
        a.setDecisionText(text);
        a.setCmoNote(FitnessSupport.clinical("note", note, 4000));
        a.setDecidedBy(user.username());
        a.setDecidedAt(now);
        a.setAppealDeadline(today.plusDays(appealWindowDays));
        a.setStatus("DECIDED");
        a.setLetterDocumentId(letter(user, a, text, today, a.getAppealDeadline()));
        timeline.record(a.getCaseId(), "IHR", label(a.getAssessmentType()) + " decided by the CMO; appeal window until " + a.getAppealDeadline());
        audit.record("IHR_CMO_DECISION", "IHR", id.toString(), AuditService.Outcome.SUCCESS, approve ? "APPROVED" : "NOT_APPROVED");
        return view(a);
    }

    @Transactional
    public IhrView appeal(CurrentUser user, UUID id, String grounds, String secondOpinionBy) {
        if (!user.hasRole(Roles.HR) && !user.hasAnyRole(Roles.OH_TEAM)) {
            throw ApiException.forbidden("Appeals are lodged through HR or Occupational Health");
        }
        IhrAssessment a = load(user, id, "IHR_APPEAL");
        if (!"DECIDED".equals(a.getStatus())) {
            throw ApiException.conflict("invalid_state", "Only decided assessments can be appealed");
        }
        LocalDate today = support.today();
        if (a.getAppealDeadline() != null && today.isAfter(a.getAppealDeadline())) {
            throw ApiException.conflict("appeal_window_closed", "The 28-day appeal window closed on " + a.getAppealDeadline());
        }
        // HR lodges on the employee's behalf: grounds must stay functional; OH may record clinical grounds (encrypted)
        String g = user.hasAnyRole(Roles.OH_TEAM) ? FitnessSupport.clinical("grounds", grounds, 4000)
                : FitnessSupport.functional("grounds", grounds, 4000);
        if (g == null) {
            throw ApiException.badRequest("grounds_required", "Appeal grounds are required");
        }
        a.setAppealGrounds(g);
        a.setSecondOpinionBy(FitnessSupport.functional("secondOpinionBy", secondOpinionBy, 200));
        a.setAppealedBy(user.username());
        a.setAppealedAt(support.clock().instant());
        a.setAppealDueBy(today.plusDays(appealDecisionDays));
        a.setStatus("APPEALED");
        support.notifyRole(Roles.CMO, "fitness.signoff-required", Map.of("caseRef", a.getCaseRef(), "level", "APPEAL"));
        timeline.record(a.getCaseId(), "IHR", "Appeal lodged; decision due by " + a.getAppealDueBy());
        audit.record("IHR_APPEAL", "IHR", id.toString(), AuditService.Outcome.SUCCESS, null);
        return view(a);
    }

    @Transactional
    public IhrView appealDecision(CurrentUser user, UUID id, boolean upheld, String decisionText) {
        requireCmo(user);
        IhrAssessment a = load(user, id, "IHR_APPEAL_DECISION");
        if (!"APPEALED".equals(a.getStatus())) {
            throw ApiException.conflict("invalid_state", "The assessment is not under appeal");
        }
        if (user.username().equals(a.getAssessedBy()) || user.username().equals(a.getDecidedBy())) {
            throw ApiException.conflict("independent_cmo_required",
                    "The appeal must be decided by a CMO other than the assessing physician and the original decision maker");
        }
        String text = FitnessSupport.functional("decisionText", decisionText, 4000);
        if (text == null) {
            throw ApiException.badRequest("decision_text_required", "decisionText is required");
        }
        LocalDate today = support.today();
        a.setAppealUpheld(upheld);
        a.setAppealDecisionText(text);
        a.setAppealDecidedBy(user.username());
        a.setAppealDecidedAt(support.clock().instant());
        a.setStatus("APPEAL_DECIDED");
        a.setAppealLetterDocumentId(letter(user, a, "Appeal outcome: " + (upheld ? "upheld" : "not upheld") + ". " + text, today, today));
        timeline.record(a.getCaseId(), "IHR", "Appeal decided: " + (upheld ? "upheld" : "not upheld"));
        audit.record("IHR_APPEAL_DECISION", "IHR", id.toString(), AuditService.Outcome.SUCCESS, upheld ? "UPHELD" : "NOT_UPHELD");
        return view(a);
    }

    /** OH roles see everything; HR (status scope) sees type, status, functional decision text and dates. */
    @Transactional(readOnly = true)
    public List<?> forCase(CurrentUser user, UUID caseId) {
        if (user.hasAnyRole(Roles.OH_TEAM)) {
            access.clinical(user, caseId, AccessPolicy.Action.READ, "IHR_READ");
            return assessments.findByCaseIdOrderByAssessedAtDesc(caseId).stream().map(this::view).toList();
        }
        if (user.hasRole(Roles.HR)) {
            access.require(user, caseId, AccessPolicy.Action.READ, DisclosureLevel.STATUS_ONLY, "IHR_READ");
            audit.record("IHR_READ", "CASE", caseId.toString(), AuditService.Outcome.SUCCESS, "HR status read");
            return assessments.findByCaseIdOrderByAssessedAtDesc(caseId).stream().map(IhrService::hrView).toList();
        }
        throw ApiException.forbidden("OH and HR only");
    }

    private UUID letter(CurrentUser user, IhrAssessment a, String decision, LocalDate effective, LocalDate appealDeadline) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("employeeName", support.employeeName(a.getEmployeeId()));
        f.put("decisionType", label(a.getAssessmentType()).toLowerCase(java.util.Locale.ROOT));
        f.put("decision", decision);
        f.put("effectiveDate", effective.toString());
        f.put("appealDeadline", appealDeadline.toString());
        f.put("cmoName", support.staffName(user.username()));
        DocumentService.DocumentView doc = documents.generate(user, "IHR_DECISION_LETTER", a.getEmployeeId(), a.getCaseId(), f);
        documents.sign(user, doc.id());
        documents.share(user, doc.id(), DocumentShare.Target.EMPLOYEE, null, null);
        documents.share(user, doc.id(), DocumentShare.Target.HR, null, null);
        return doc.id();
    }

    private IhrAssessment load(CurrentUser user, UUID id, String auditAction) {
        IhrAssessment a = assessments.findById(id).orElseThrow(() -> ApiException.notFound("Assessment"));
        if (user.hasAnyRole(Roles.OH_TEAM)) {
            access.clinical(user, a.getCaseId(), AccessPolicy.Action.UPDATE, auditAction);
        } else {
            access.require(user, a.getCaseId(), AccessPolicy.Action.READ, DisclosureLevel.STATUS_ONLY, auditAction);
        }
        return a;
    }

    private static void requireCmo(CurrentUser user) {
        if (!user.hasRole(Roles.CMO)) {
            throw ApiException.forbidden("CMO only");
        }
    }

    static String label(String type) {
        return "REDEPLOYMENT".equals(type) ? "Redeployment" : "Ill-health retirement";
    }

    private IhrView view(IhrAssessment a) {
        return new IhrView(a.getId(), a.getCaseId(), a.getCaseRef(), a.getEmployeeId(), a.getAssessmentType(), a.getFindings(),
                a.getRecommendation(), a.getPolicyReference(), a.getStatus(), a.getAssessedBy(), a.getAssessedAt(), a.getApproved(),
                a.getDecisionText(), a.getCmoNote(), a.getDecidedBy(), a.getDecidedAt(), a.getAppealDeadline(), a.getLetterDocumentId(),
                a.getAppealGrounds(), a.getSecondOpinionBy(), a.getAppealedBy(), a.getAppealedAt(), a.getAppealDueBy(), a.getAppealUpheld(),
                a.getAppealDecisionText(), a.getAppealDecidedBy(), a.getAppealDecidedAt(), a.getAppealLetterDocumentId());
    }

    static IhrHrView hrView(IhrAssessment a) {
        return new IhrHrView(a.getId(), a.getAssessmentType(), a.getStatus(), a.getDecisionText(), a.getAssessedAt(), a.getDecidedAt(),
                a.getAppealDeadline(), a.getAppealedAt(), a.getAppealDueBy(), a.getAppealDecisionText(), a.getAppealDecidedAt());
    }
}
