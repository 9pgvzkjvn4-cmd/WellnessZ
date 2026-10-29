package com.wellnessz.e05.decision;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.CaseService;
import com.wellnessz.casekernel.FitnessFeed;
import com.wellnessz.casekernel.model.FitnessStatus;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.support.CaseAccess;
import com.wellnessz.casekernel.support.FitnessClearanceGuard;
import com.wellnessz.casekernel.support.CaseTimeline;
import com.wellnessz.casekernel.support.EmployeeRecordAccess;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.common.model.Roles;
import com.wellnessz.documents.DocumentService;
import com.wellnessz.documents.model.DocumentShare;
import com.wellnessz.e02.model.DurationGuideline;
import com.wellnessz.e02.model.DurationGuidelineRepository;
import com.wellnessz.e05.E05Events;
import com.wellnessz.e05.model.FitnessDecisionEntity;
import com.wellnessz.e05.model.FitnessDecisionRepository;
import com.wellnessz.e05.restriction.RestrictionService;
import com.wellnessz.e05.support.FitnessSupport;
import com.wellnessz.e05.support.RestrictionRef;
import com.wellnessz.integration.outbox.EventBus;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.masterdata.fitness.FitnessStandard;
import com.wellnessz.masterdata.fitness.FitnessStandardRepository;
import com.wellnessz.masterdata.fitness.JobDemandProfile;
import com.wellnessz.masterdata.fitness.JobDemandProfileRepository;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Fitness decision module (E05-F01, E05-BL-002/003/020): decisions against versioned role standards with limitation codes,
 * catalogue restrictions, validity and review dates; licensed standards decided only by AME / CMO; permanent unfitness
 * awaiting CMO sign-off; kernel status + status feeds; draft fitness certificate without diagnosis; duration comparison.
 */
@Service
public class FitnessDecisionService {

    static final int MAX_LIMITATION_CODES = 15;
    static final Set<String> CREW_STANDARDS = Set.of("CAA_CLASS1", "CAA_CLASS2", "CABIN_CREW");

    private final FitnessDecisionRepository decisions;
    private final FitnessStandardRepository standards;
    private final JobDemandProfileRepository jobDemands;
    private final DurationGuidelineRepository guidelines;
    private final CaseService caseService;
    private final CaseAccess access;
    private final EmployeeRecordAccess records;
    private final CaseTimeline timeline;
    private final RestrictionService restrictions;
    private final DocumentService documents;
    private final FitnessSupport support;
    private final EventBus bus;
    private final AuditService audit;
    private final List<FitnessClearanceGuard> clearanceGuards;

    public FitnessDecisionService(FitnessDecisionRepository decisions, FitnessStandardRepository standards,
                                  JobDemandProfileRepository jobDemands, DurationGuidelineRepository guidelines, CaseService caseService,
                                  CaseAccess access, EmployeeRecordAccess records, CaseTimeline timeline, RestrictionService restrictions,
                                  DocumentService documents, FitnessSupport support, EventBus bus, AuditService audit,
                                  List<FitnessClearanceGuard> clearanceGuards) {
        this.decisions = decisions;
        this.standards = standards;
        this.jobDemands = jobDemands;
        this.guidelines = guidelines;
        this.caseService = caseService;
        this.access = access;
        this.records = records;
        this.timeline = timeline;
        this.restrictions = restrictions;
        this.documents = documents;
        this.support = support;
        this.bus = bus;
        this.audit = audit;
        this.clearanceGuards = List.copyOf(clearanceGuards);
    }

    // ------------------------------------------------------------------ API types

    public record DecisionCommand(String standardCode, String outcome, List<String> limitationCodes, List<RestrictionRef.Input> restrictions,
                                  LocalDate validFrom, LocalDate validUntil, LocalDate reviewDate, String rationale,
                                  String durationOverrideReason, UUID encounterId, Boolean generateCertificate, LocalDate examinationDate,
                                  boolean crewAssessment) {
    }

    public record DecisionView(UUID id, UUID caseId, String caseRef, String employeeId, String standardCode, String standardTitle,
                               int standardVersion, String outcome, String kernelStatus, List<String> limitationCodes,
                               List<RestrictionRef.View> restrictions, LocalDate validFrom, LocalDate validUntil, LocalDate reviewDate,
                               String rationale, String durationOverrideReason, String status, String requiredSignoffRole, String decidedBy,
                               Instant decidedAt, String signoffBy, Instant signoffAt, String signoffNote, UUID certificateDocumentId,
                               boolean crewAssessment, LocalDate examinationDate, UUID reportDocumentId) {
    }

    public record DurationComparison(String icdCode, String guidelineTitle, Integer expectedDays, Long actualDays, Long varianceDays,
                                     Boolean exceeded, String source) {
    }

    // ------------------------------------------------------------------ decide

    @Transactional
    public DecisionView decide(CurrentUser user, UUID caseId, DecisionCommand cmd) {
        OhCase c = access.clinical(user, caseId, AccessPolicy.Action.UPDATE, "FITNESS_DECISION");
        if (!user.hasAnyRole(Roles.OH_CLINICIAN, Roles.OH_PHYSICIAN, Roles.AME, Roles.CMO)) {
            throw ApiException.forbidden("Fitness decisions are recorded by physicians, AMEs or OH clinicians");
        }
        FitnessStandard standard = standards.current(cmd.standardCode())
                .orElseThrow(() -> ApiException.badRequest("unknown_standard", "Unknown or inactive fitness standard"));
        requireDeciderRole(user, standard);
        if (!DecisionRules.OUTCOMES.contains(cmd.outcome())) {
            throw ApiException.badRequest("invalid_outcome", "Unknown fitness outcome");
        }
        if ("FIT".equals(cmd.outcome()) || "FIT_WITH_LIMITATIONS".equals(cmd.outcome())) {
            for (FitnessClearanceGuard g : clearanceGuards) {
                g.blockingReason(c.getEmployeeId(), cmd.outcome()).ifPresent(reason -> {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "clearance_required", reason);
                });
            }
        }
        List<String> codes = limitationCodes(standard, cmd.limitationCodes());
        List<RestrictionRef.View> views = support.resolve(cmd.restrictions());
        if ("FIT_WITH_LIMITATIONS".equals(cmd.outcome()) && codes.isEmpty() && views.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "limitation_required",
                    "FIT_WITH_LIMITATIONS requires at least one restriction or limitation code");
        }
        LocalDate validFrom = cmd.validFrom() == null ? support.today() : cmd.validFrom();
        Integer age = DecisionRules.age(null, validFrom); // the HRIS feed carries no date of birth today
        LocalDate validUntil = cmd.validUntil() != null ? cmd.validUntil()
                : DecisionRules.defaultValidUntil(cmd.outcome(), validFrom, standard.getValidityMonths(), standard.getValidityMonthsOver40(), age);
        if (validUntil != null && validUntil.isBefore(validFrom)) {
            throw ApiException.badRequest("invalid_validity", "validUntil must not be before validFrom");
        }
        LocalDate reviewDate = cmd.reviewDate() != null ? cmd.reviewDate() : "TEMPORARILY_UNFIT".equals(cmd.outcome()) ? validUntil : null;
        if (reviewDate != null && reviewDate.isBefore(validFrom)) {
            throw ApiException.badRequest("invalid_review_date", "reviewDate must not be before validFrom");
        }
        String override = FitnessSupport.clinical("durationOverrideReason", cmd.durationOverrideReason(), 1000);
        if ("TEMPORARILY_UNFIT".equals(cmd.outcome()) && override == null) {
            DurationComparison projected = compare(c, validUntil == null ? support.today() : validUntil);
            if (Boolean.TRUE.equals(projected.exceeded())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "duration_override_required",
                        "The projected absence (" + projected.actualDays() + " days) exceeds the duration guideline ("
                                + projected.expectedDays() + " days) – record an override reason");
            }
        }
        FitnessDecisionEntity d = FitnessDecisionEntity.create();
        d.setCaseId(c.getId());
        d.setCaseRef(c.getCaseRef());
        d.setEmployeeId(c.getEmployeeId());
        d.setStandardCode(standard.getCode());
        d.setStandardVersion(standard.getVersion());
        d.setOutcome(cmd.outcome());
        d.setLimitationCodes(codes.isEmpty() ? null : String.join(",", codes));
        d.setRestrictions(support.write(views));
        d.setValidFrom(validFrom);
        d.setValidUntil(validUntil);
        d.setReviewDate(reviewDate);
        d.setRationale(FitnessSupport.clinical("rationale", cmd.rationale(), 4000));
        d.setDurationOverrideReason(override);
        d.setEncounterId(cmd.encounterId());
        d.setDecidedBy(user.username());
        d.setDecidedAt(support.clock().instant());
        d.setCrewAssessment(cmd.crewAssessment());
        d.setExaminationDate(cmd.examinationDate());
        boolean needsSignoff = "PERMANENTLY_UNFIT".equals(cmd.outcome());
        if (needsSignoff) {
            d.setStatus(DecisionRules.AWAITING_SIGNOFF);
            d.setRequiredSignoffRole(Roles.CMO);
            d.setKernelStatus(FitnessStatus.UNDER_REVIEW.name());
            decisions.save(d);
            caseService.applyFitnessDecision(user, c.getId(), FitnessStatus.UNDER_REVIEW, null,
                    new FitnessFeed(validFrom, null, null, List.of()));
            support.notifyRole(Roles.CMO, "fitness.signoff-required", Map.of("caseRef", c.getCaseRef(), "level", "CMO"));
            timeline.record(c.getId(), "FITNESS_DECISION", "Fitness decision against " + standard.getCode() + " awaiting CMO sign-off");
        } else {
            d.setStatus(DecisionRules.FINAL);
            d.setKernelStatus(DecisionRules.kernelStatus(cmd.outcome(), true).name());
            decisions.save(d);
            finalise(user, c, d, standard, views, codes, !Boolean.FALSE.equals(cmd.generateCertificate()) && !cmd.crewAssessment());
        }
        audit.record("FITNESS_DECISION", "CASE", caseId.toString(), AuditService.Outcome.SUCCESS,
                standard.getCode() + " v" + standard.getVersion() + " " + cmd.outcome() + " " + d.getStatus());
        return view(d, standard);
    }

    /** FINAL decision: supersede earlier ones, kernel status + feeds, restriction instances, draft certificate, in-process event. */
    private void finalise(CurrentUser user, OhCase c, FitnessDecisionEntity d, FitnessStandard standard, List<RestrictionRef.View> views,
                          List<String> codes, boolean certificate) {
        for (FitnessDecisionEntity other : decisions.findByEmployeeIdOrderByDecidedAtDesc(d.getEmployeeId())) {
            if (!other.getId().equals(d.getId()) && other.getStandardCode().equals(d.getStandardCode())
                    && DecisionRules.FINAL.equals(other.getStatus())) {
                other.setStatus(DecisionRules.SUPERSEDED);
            }
        }
        FitnessStatus status = DecisionRules.kernelStatus(d.getOutcome(), true);
        d.setKernelStatus(status.name());
        caseService.applyFitnessDecision(user, c.getId(), status, DecisionRules.kernelUntil(d.getOutcome(), d.getValidUntil(), d.getReviewDate()),
                new FitnessFeed(d.getValidFrom(), d.getValidUntil(), d.getReviewDate(), DecisionRules.operational(codes)));
        restrictions.closeSuperseded(c.getId(), d.getId(), user.username());
        restrictions.create(c, d.getId(), views, d.getValidFrom(), d.getValidUntil() != null ? d.getValidUntil() : d.getReviewDate(),
                user.username());
        if (certificate && !"PENDING".equals(d.getOutcome())) {
            d.setCertificateDocumentId(documents.generate(user, "FITNESS_CERTIFICATE", c.getEmployeeId(), c.getId(),
                    certificateFields(user, c, d, standard, views, codes)).id());
        }
        bus.publish(new E05Events.FitnessDecisionMade(d.getId().toString(), c.getId().toString(), c.getEmployeeId(), d.getOutcome(),
                d.getStandardCode()));
        timeline.record(c.getId(), "FITNESS_DECISION", "Fitness decision against " + standard.getCode() + ": " + d.getOutcome());
    }

    private Map<String, String> certificateFields(CurrentUser user, OhCase c, FitnessDecisionEntity d, FitnessStandard standard,
                                                  List<RestrictionRef.View> views, List<String> codes) {
        Employee e = support.employee(c.getEmployeeId()).orElse(null);
        List<String> functional = new ArrayList<>(views.stream().map(RestrictionRef.View::label).toList());
        DecisionRules.operational(codes).forEach(code -> functional.add(code + " – " + LimitationCodes.labels().get(code)));
        Map<String, String> f = new LinkedHashMap<>();
        f.put("employeeName", e != null ? e.getDisplayName() : c.getEmployeeDisplayName());
        f.put("employeeId", c.getEmployeeId());
        String job = e != null ? e.getJobCode() : c.getJobCode();
        f.put("jobTitle", job == null ? null : jobDemands.findById(job).map(JobDemandProfile::getTitle).orElse(job));
        f.put("standardTitle", standard.getTitle());
        f.put("outcome", outcomeLabel(d.getOutcome()));
        f.put("restrictions", functional.isEmpty() ? "None" : String.join("; ", functional));
        f.put("validFrom", d.getValidFrom().toString());
        f.put("validUntil", d.getValidUntil() == null ? null : d.getValidUntil().toString());
        f.put("reviewDate", d.getReviewDate() == null ? null : d.getReviewDate().toString());
        f.put("issuedBy", support.staffName(user.username()));
        f.values().removeIf(v -> v == null);
        return f;
    }

    public static String outcomeLabel(String outcome) {
        return switch (outcome) {
            case "FIT" -> "Fit";
            case "FIT_WITH_LIMITATIONS" -> "Fit with limitations";
            case "TEMPORARILY_UNFIT" -> "Temporarily unfit";
            case "PERMANENTLY_UNFIT" -> "Permanently unfit";
            default -> "Assessment pending";
        };
    }

    private static void requireDeciderRole(CurrentUser user, FitnessStandard standard) {
        String required = standard.getRequiresSignoffRole();
        if (Roles.CMO.equals(required) && !user.hasRole(Roles.CMO)) {
            throw ApiException.forbidden("This standard can only be decided by the CMO");
        }
        if (Roles.AME.equals(required) && !user.hasAnyRole(Roles.AME, Roles.CMO)) {
            throw ApiException.forbidden("Licensed standards can only be decided by an AME or the CMO");
        }
    }

    private static List<String> limitationCodes(FitnessStandard standard, List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        if (requested.size() > MAX_LIMITATION_CODES) {
            throw ApiException.badRequest("too_many_limitation_codes", "At most " + MAX_LIMITATION_CODES + " limitation codes");
        }
        List<String> allowed = standard.limitationCodeList();
        Set<String> out = new LinkedHashSet<>();
        for (String code : requested) {
            if (code == null || !allowed.contains(code)) {
                throw ApiException.badRequest("invalid_limitation_code", "Limitation code not defined for " + standard.getCode());
            }
            out.add(code);
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------ sign-off

    @Transactional
    public DecisionView signoff(CurrentUser user, UUID id, boolean approve, String note) {
        FitnessDecisionEntity d = decisions.findById(id).orElseThrow(() -> ApiException.notFound("Fitness decision"));
        OhCase c = access.clinical(user, d.getCaseId(), AccessPolicy.Action.UPDATE, "FITNESS_SIGNOFF");
        if (!DecisionRules.AWAITING_SIGNOFF.equals(d.getStatus())) {
            throw ApiException.conflict("not_awaiting_signoff", "This decision is not awaiting sign-off");
        }
        if (!canSign(user, d)) {
            throw ApiException.forbidden("Sign-off requires the " + d.getRequiredSignoffRole() + " role");
        }
        if (user.username().equals(d.getDecidedBy())) {
            throw ApiException.conflict("four_eyes_required", "The decision must be signed off by someone other than the decider");
        }
        FitnessStandard standard = standards.findById(new FitnessStandard.Key(d.getStandardCode(), d.getStandardVersion()))
                .orElseThrow(() -> ApiException.notFound("Fitness standard"));
        d.setSignoffBy(user.username());
        d.setSignoffAt(support.clock().instant());
        d.setSignoffNote(FitnessSupport.clinical("note", note, 2000));
        if (approve) {
            d.setStatus(DecisionRules.FINAL);
            List<String> codes = d.getLimitationCodes() == null ? List.of() : List.of(d.getLimitationCodes().split(","));
            finalise(user, c, d, standard, support.read(d.getRestrictions()), codes, !d.isCrewAssessment());
            timeline.record(c.getId(), "FITNESS_SIGNOFF", "Fitness decision approved by " + d.getRequiredSignoffRole());
        } else {
            d.setStatus(DecisionRules.REJECTED);
            timeline.record(c.getId(), "FITNESS_SIGNOFF", "Fitness decision not approved by " + d.getRequiredSignoffRole()
                    + " – case remains under review");
        }
        audit.record("FITNESS_SIGNOFF", "FITNESS_DECISION", id.toString(), AuditService.Outcome.SUCCESS, approve ? "APPROVED" : "REJECTED");
        return view(d, standard);
    }

    static boolean canSign(CurrentUser user, FitnessDecisionEntity d) {
        String required = d.getRequiredSignoffRole();
        return required != null && (user.hasRole(required) || (Roles.AME.equals(required) && user.hasRole(Roles.CMO)));
    }

    @Transactional(readOnly = true)
    public List<DecisionView> awaitingSignoff(CurrentUser user) {
        List<DecisionView> out = new ArrayList<>();
        for (FitnessDecisionEntity d : decisions.findByStatusOrderByDecidedAtAsc(DecisionRules.AWAITING_SIGNOFF)) {
            if (!canSign(user, d)) {
                continue;
            }
            Employee e = support.employee(d.getEmployeeId()).orElse(null);
            if (e != null && records.canSeeClinical(user, e, DataClassification.CLINICAL)) {
                out.add(view(d, null));
            }
        }
        audit.record("FITNESS_SIGNOFF_QUEUE", "FITNESS_DECISION", null, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<DecisionView> history(CurrentUser user, UUID caseId) {
        access.clinical(user, caseId, AccessPolicy.Action.READ, "FITNESS_DECISIONS_READ");
        return decisions.findByCaseIdOrderByDecidedAtDesc(caseId).stream().map(d -> view(d, null)).toList();
    }

    /** Current decision per standard (FINAL or awaiting sign-off) for an employee. */
    @Transactional(readOnly = true)
    public List<DecisionView> employeeFitness(CurrentUser user, String employeeId) {
        records.requireClinical(user, employeeId, DataClassification.CLINICAL, AccessPolicy.Action.READ, "EMPLOYEE_FITNESS_READ");
        Map<String, DecisionView> current = new LinkedHashMap<>();
        for (FitnessDecisionEntity d : decisions.findByEmployeeIdOrderByDecidedAtDesc(employeeId)) {
            if (DecisionRules.FINAL.equals(d.getStatus()) || DecisionRules.AWAITING_SIGNOFF.equals(d.getStatus())) {
                current.putIfAbsent(d.getStandardCode(), view(d, null));
            }
        }
        audit.record("EMPLOYEE_FITNESS_READ", "EMPLOYEE", employeeId, AuditService.Outcome.SUCCESS, null);
        return List.copyOf(current.values());
    }

    /** Latest FINAL decision of an employee (chart summary). */
    @Transactional(readOnly = true)
    public Optional<FitnessDecisionEntity> currentDecision(String employeeId) {
        return decisions.findByEmployeeIdOrderByDecidedAtDesc(employeeId).stream()
                .filter(d -> DecisionRules.FINAL.equals(d.getStatus())).findFirst();
    }

    @Transactional(readOnly = true)
    public List<FitnessDecisionEntity> pendingSignoff(String employeeId) {
        return decisions.findByEmployeeIdOrderByDecidedAtDesc(employeeId).stream()
                .filter(d -> DecisionRules.AWAITING_SIGNOFF.equals(d.getStatus())).toList();
    }

    @Transactional
    public void attachReport(UUID decisionId, UUID documentId) {
        decisions.findById(decisionId).ifPresent(d -> d.setReportDocumentId(documentId));
    }

    // ------------------------------------------------------------------ duration comparison (E05-BL-020)

    @Transactional(readOnly = true)
    public DurationComparison durationComparison(CurrentUser user, UUID caseId) {
        OhCase c = access.clinical(user, caseId, AccessPolicy.Action.READ, "DURATION_COMPARISON");
        LocalDate end = decisions.findByCaseIdOrderByDecidedAtDesc(caseId).stream()
                .filter(d -> DecisionRules.FINAL.equals(d.getStatus()) || DecisionRules.SUPERSEDED.equals(d.getStatus()))
                .filter(d -> "FIT".equals(d.getOutcome()) || "FIT_WITH_LIMITATIONS".equals(d.getOutcome()))
                .map(FitnessDecisionEntity::getValidFrom).min(LocalDate::compareTo).orElse(support.today());
        return compare(c, end);
    }

    DurationComparison compare(OhCase c, LocalDate end) {
        String icd = c.getPrimaryDiagnosisCode();
        Optional<DurationGuideline> g = guideline(icd);
        if (g.isEmpty()) {
            return new DurationComparison(icd, null, null, null, null, null, null);
        }
        LocalDate start = c.getOccurredOn() != null ? c.getOccurredOn() : LocalDate.ofInstant(c.getCreatedAt(), support.clock().getZone());
        long actual = Math.max(0, ChronoUnit.DAYS.between(start, end));
        int expected = g.get().getExpectedDays();
        return new DurationComparison(icd, g.get().getTitle(), expected, actual, actual - expected, actual > expected, g.get().getSource());
    }

    /** Exact ICD-10 match first, then progressively less specific codes (S93.40 → S93.4 → S93). */
    Optional<DurationGuideline> guideline(String icd) {
        if (icd == null || icd.isBlank() || icd.length() > 10) {
            return Optional.empty();
        }
        String code = icd.strip().toUpperCase(java.util.Locale.ROOT);
        while (code.length() >= 3) {
            Optional<DurationGuideline> g = guidelines.findById(code);
            if (g.isPresent()) {
                return g;
            }
            code = code.substring(0, code.length() - 1);
            if (code.endsWith(".")) {
                code = code.substring(0, code.length() - 1);
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ views

    public DecisionView view(FitnessDecisionEntity d, FitnessStandard known) {
        FitnessStandard s = known != null ? known
                : standards.findById(new FitnessStandard.Key(d.getStandardCode(), d.getStandardVersion())).orElse(null);
        return new DecisionView(d.getId(), d.getCaseId(), d.getCaseRef(), d.getEmployeeId(), d.getStandardCode(),
                s == null ? d.getStandardCode() : s.getTitle(), d.getStandardVersion(), d.getOutcome(), d.getKernelStatus(),
                d.getLimitationCodes() == null ? List.of() : List.of(d.getLimitationCodes().split(",")), support.read(d.getRestrictions()),
                d.getValidFrom(), d.getValidUntil(), d.getReviewDate(), d.getRationale(), d.getDurationOverrideReason(), d.getStatus(),
                d.getRequiredSignoffRole(), d.getDecidedBy(), d.getDecidedAt(), d.getSignoffBy(), d.getSignoffAt(), d.getSignoffNote(),
                d.getCertificateDocumentId(), d.isCrewAssessment(), d.getExaminationDate(), d.getReportDocumentId());
    }

    /** Shares a signed document with the employee (crew reports); used by the crew module. */
    public void shareWithEmployee(CurrentUser user, UUID documentId) {
        documents.share(user, documentId, DocumentShare.Target.EMPLOYEE, null, null);
    }
}
