package com.wellnessz.e06.advice;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.CaseService;
import com.wellnessz.casekernel.model.CaseCategory;
import com.wellnessz.casekernel.model.CaseSource;
import com.wellnessz.casekernel.model.CaseType;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OhCaseRepository;
import com.wellnessz.casekernel.model.OpenCaseCommand;
import com.wellnessz.casekernel.model.Urgency;
import com.wellnessz.casekernel.support.CaseTimeline;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.documents.DocumentService;
import com.wellnessz.documents.model.DocumentShare;
import com.wellnessz.e05.support.FitnessSupport;
import com.wellnessz.e06.E06Events;
import com.wellnessz.e06.model.AbsenceEnums.AdviceFitness;
import com.wellnessz.e06.model.AbsenceEnums.AdviceStatus;
import com.wellnessz.e06.model.AbsenceEnums.Prognosis;
import com.wellnessz.e06.model.AdviceReport;
import com.wellnessz.e06.model.AdviceReportRepository;
import com.wellnessz.e06.model.AdviceRequest;
import com.wellnessz.e06.model.AdviceRequestRepository;
import com.wellnessz.e06.model.E06EpisodeRepository;
import com.wellnessz.e06.support.AbsenceSupport;
import com.wellnessz.integration.outbox.EventBus;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Management advice requests and OH advice reports (E06-F05). Managers / HR ask questions from the library; OH accepts,
 * drafts a functional report (every text screened – no diagnosis), signs it; the employee reviews it (consent → the signed
 * OH_ADVICE_REPORT is generated and shared with the requester, disclosure logged; decline → withheld, new versions possible).
 */
@Service("absenceAdviceService")
public class AdviceService {

    public static final String DRAFT = "DRAFT";
    public static final String SUPERSEDED = "SUPERSEDED";
    static final int MAX_ANSWER = 400;

    /** A library question: full wording and the short label used in the released document. */
    record LibraryQuestion(String text, String label) {
    }

    /** Question library (code → wording). */
    static final Map<String, LibraryQuestion> QUESTIONS;

    static {
        Map<String, LibraryQuestion> q = new LinkedHashMap<>();
        q.put("FIT_FOR_ROLE", new LibraryQuestion("Is the employee fit to carry out their current role?", "Fit for role"));
        q.put("ADJUSTMENTS", new LibraryQuestion("What workplace adjustments would help the employee to remain at or return to work?", "Adjustments"));
        q.put("EXPECTED_RTW", new LibraryQuestion("When is the employee likely to return to work?", "Expected return"));
        q.put("PHASED_RETURN", new LibraryQuestion("Would a phased return to work be appropriate?", "Phased return"));
        q.put("ABLE_TO_ATTEND_MEETINGS", new LibraryQuestion("Is the employee able to attend meetings about their absence?", "Attending meetings"));
        q.put("DISABILITY_PROVISIONS_LIKELY", new LibraryQuestion("Are the disability provisions of employment law likely to apply?", "Disability provisions"));
        q.put("WORK_CONTRIBUTION", new LibraryQuestion("Is work contributing to the absence, and can anything at work help?", "Work contribution"));
        q.put("FURTHER_REVIEW", new LibraryQuestion("Will Occupational Health review the employee again, and when?", "Further review"));
        QUESTIONS = java.util.Collections.unmodifiableMap(q);
    }

    static final Set<String> OPEN_FOR_WITHDRAW = Set.of(AdviceStatus.SUBMITTED.name(), AdviceStatus.ACCEPTED.name(),
            AdviceStatus.REPORT_DRAFTED.name(), AdviceStatus.AWAITING_EMPLOYEE.name());

    private final AdviceRequestRepository requests;
    private final AdviceReportRepository reports;
    private final E06EpisodeRepository episodes;
    private final OhCaseRepository caseRepo;
    private final CaseService cases;
    private final CaseTimeline timeline;
    private final DocumentService documents;
    private final EventBus bus;
    private final JdbcTemplate jdbc;
    private final AbsenceSupport support;
    private final AuditService audit;

    public AdviceService(AdviceRequestRepository requests, AdviceReportRepository reports, E06EpisodeRepository episodes, OhCaseRepository caseRepo,
                         CaseService cases, CaseTimeline timeline, DocumentService documents, EventBus bus, JdbcTemplate jdbc, AbsenceSupport support,
                         AuditService audit) {
        this.requests = requests;
        this.reports = reports;
        this.episodes = episodes;
        this.caseRepo = caseRepo;
        this.cases = cases;
        this.timeline = timeline;
        this.documents = documents;
        this.bus = bus;
        this.jdbc = jdbc;
        this.support = support;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ views

    public record Question(String code, String text) {
    }

    /** The released report as the requester sees it (functional wording only). */
    public record ReportView(String fitness, String adjustments, String restrictions, String prognosis, LocalDate expectedReturn,
                             LocalDate reviewDate, Map<String, String> answers, String employeeComment, UUID documentId, Instant releasedAt) {
    }

    /** OH view of a report version. */
    public record OhReportView(UUID id, int version, String status, String fitness, String adjustments, String restrictions, String prognosis,
                               LocalDate expectedReturn, LocalDate reviewDate, Map<String, String> answers, String draftedBy, Instant draftedAt,
                               String signedBy, Instant signedAt, String employeeDecision, String employeeComment, String declineReason,
                               Instant decidedAt, UUID documentId, Instant releasedAt) {
    }

    public record AdviceRequestView(UUID id, String ref, String employeeId, String displayName, String status, List<String> questionCodes,
                                    String requestedBy, String requesterRole, Instant requestedAt, Instant slaDueAt, String caseRef, UUID caseId,
                                    UUID episodeId, String context, ReportView report, OhReportView currentReport, String acceptedBy) {
    }

    public record EmployeeReport(UUID id, String requestRef, String requestedByRole, String status, ReportView report, Instant createdAt) {
    }

    public record ReportCommand(String fitness, String adjustments, String restrictions, String prognosis, LocalDate expectedReturn,
                                LocalDate reviewDate, Map<String, String> answers) {
    }

    public List<Question> questions() {
        return QUESTIONS.entrySet().stream().map(e -> new Question(e.getKey(), e.getValue().text())).toList();
    }

    // ------------------------------------------------------------------ requester (manager / HR)

    @Transactional
    public AdviceRequestView submit(CurrentUser user, String employeeId, List<String> questionCodes, String context, UUID episodeId) {
        if (!user.hasAnyRole(Roles.MANAGER, Roles.HR)) {
            throw ApiException.forbidden("Managers and HR only");
        }
        Employee e = support.requireInScope(user, employeeId, "ADVICE_REQUEST_CREATE");
        Set<String> codes = new LinkedHashSet<>(questionCodes == null ? List.of() : questionCodes);
        if (codes.isEmpty() || !QUESTIONS.keySet().containsAll(codes)) {
            throw ApiException.badRequest("invalid_questions", "Choose at least one question from the library");
        }
        if (episodeId != null && episodes.findById(episodeId).filter(x -> x.getEmployeeId().equals(employeeId)).isEmpty()) {
            throw ApiException.notFound("Episode");
        }
        Long seq = jdbc.queryForObject("select nextval('absence.advice_ref_seq')", Long.class);
        String digits = seq == null ? "0" : seq.toString();
        String number = "0".repeat(Math.max(0, 6 - digits.length())) + digits;
        LocalDate today = support.today();
        AdviceRequest r = AdviceRequest.create();
        r.setRef("ADV-" + today.getYear() + "-" + number);
        r.setEmployeeId(e.getEmployeeId());
        r.setRequestedBy(user.username());
        r.setRequesterRole(user.hasRole(Roles.MANAGER) ? "MANAGER" : "HR");
        r.setRequesterEmployeeId(user.employeeId());
        r.setQuestionCodes(String.join(",", codes));
        r.setContext(FitnessSupport.clinical("context", context, 2000));
        r.setEpisodeId(episodeId);
        r.setStatus(AdviceStatus.SUBMITTED.name());
        r.setRequestedAt(support.now());
        LocalDate due = AbsenceSupport.plusWorkingDays(today, support.intConfig("absence.advice.sla-working-days", 5));
        r.setSlaDueAt(due.atTime(17, 0).toInstant(ZoneOffset.UTC));
        r.setUpdatedAt(support.now());
        requests.save(r);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("caseRef", r.getRef());
        params.put("unit", support.unit(e));
        params.put("dueDate", due.toString());
        support.notifyRole(Roles.CASE_MANAGER, "absence.advice-requested", params, "#/absence/advice");
        audit.record("ADVICE_REQUEST_CREATE", "ADVICE_REQUEST", r.getId().toString(), AuditService.Outcome.SUCCESS, r.getRef());
        return view(r, Audience.REQUESTER);
    }

    @Transactional(readOnly = true)
    public List<AdviceRequestView> workspace(CurrentUser user) {
        if (!user.hasAnyRole(Roles.MANAGER, Roles.HR)) {
            throw ApiException.forbidden("Managers and HR only");
        }
        List<AdviceRequestView> out = new ArrayList<>();
        for (AdviceRequest r : requests.findAllByOrderByRequestedAtDesc()) {
            boolean mine = user.username().equals(r.getRequestedBy());
            if (mine || support.inScope(user, support.employee(r.getEmployeeId()).orElse(null))) {
                out.add(view(r, mine ? Audience.REQUESTER : Audience.WORKSPACE));
            }
        }
        audit.record("ADVICE_REQUESTS_WORKSPACE_READ", "ADVICE_REQUEST", null, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    @Transactional
    public AdviceRequestView withdraw(CurrentUser user, UUID id) {
        AdviceRequest r = requests.findById(id).filter(x -> user.username().equals(x.getRequestedBy()))
                .orElseThrow(() -> ApiException.notFound("Advice request"));
        if (!OPEN_FOR_WITHDRAW.contains(r.getStatus())) {
            throw ApiException.conflict("invalid_state", "The request can no longer be withdrawn");
        }
        r.setStatus(AdviceStatus.WITHDRAWN.name());
        r.setWithdrawnAt(support.now());
        r.setUpdatedAt(support.now());
        latest(r).filter(x -> DRAFT.equals(x.getStatus()) || AdviceStatus.AWAITING_EMPLOYEE.name().equals(x.getStatus()))
                .ifPresent(x -> x.setStatus(SUPERSEDED));
        if (r.getCaseId() != null) {
            timeline.record(r.getCaseId(), "ADVICE_WITHDRAWN", "Advice request " + r.getRef() + " withdrawn by the requester");
        }
        audit.record("ADVICE_REQUEST_WITHDRAW", "ADVICE_REQUEST", id.toString(), AuditService.Outcome.SUCCESS, null);
        return view(r, Audience.REQUESTER);
    }

    // ------------------------------------------------------------------ OH

    @Transactional(readOnly = true)
    public List<AdviceRequestView> ohList(CurrentUser user, String status) {
        List<AdviceRequestView> out = new ArrayList<>();
        for (AdviceRequest r : status == null || status.isBlank() ? requests.findAllByOrderByRequestedAtDesc()
                : requests.findByStatusOrderByRequestedAtAsc(status)) {
            if (support.canSeeClinical(user, support.employee(r.getEmployeeId()).orElse(null))) {
                out.add(view(r, Audience.OH));
            }
        }
        audit.record("ADVICE_REQUESTS_READ", "ADVICE_REQUEST", null, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    @Transactional
    public AdviceRequestView accept(CurrentUser user, UUID id) {
        AdviceRequest r = loadOh(user, id, "ADVICE_REQUEST_ACCEPT");
        if (!AdviceStatus.SUBMITTED.name().equals(r.getStatus())) {
            throw ApiException.conflict("invalid_state", "Only submitted requests can be accepted");
        }
        OhCase c = caseRepo.findByEmployeeIdOrderByCreatedAtDesc(r.getEmployeeId()).stream()
                .filter(x -> x.getCaseType() == CaseType.ABSENCE && x.isOpen()).findFirst()
                .orElseGet(() -> cases.openIdempotent(OpenCaseCommand.builder(r.getEmployeeId(), CaseCategory.OTHER,
                                "HR".equals(r.getRequesterRole()) ? CaseSource.HR_REFERRAL : CaseSource.MANAGER_REFERRAL)
                        .caseType(CaseType.ABSENCE).sourceRef("advice:" + r.getRef()).urgency(Urgency.ROUTINE).build()));
        r.setCaseId(c.getId());
        r.setCaseRef(c.getCaseRef());
        r.setStatus(AdviceStatus.ACCEPTED.name());
        r.setAcceptedBy(user.username());
        r.setAcceptedAt(support.now());
        r.setUpdatedAt(support.now());
        if (c.getOwnerUsername() == null) {
            c.route(c.getQueueCode(), user.username(), support.now());
        }
        timeline.record(c.getId(), "ADVICE_ACCEPTED", "Management advice request " + r.getRef() + " accepted by Occupational Health");
        audit.record("ADVICE_REQUEST_ACCEPT", "ADVICE_REQUEST", id.toString(), AuditService.Outcome.SUCCESS, c.getCaseRef());
        return view(r, Audience.OH);
    }

    @Transactional
    public AdviceRequestView draft(CurrentUser user, UUID id, ReportCommand cmd) {
        AdviceRequest r = loadOh(user, id, "ADVICE_REPORT_DRAFT");
        if (!Set.of(AdviceStatus.ACCEPTED.name(), AdviceStatus.REPORT_DRAFTED.name(), AdviceStatus.WITHHELD.name()).contains(r.getStatus())) {
            throw ApiException.conflict("invalid_state", "Accept the request before drafting a report (or wait for the employee's decision)");
        }
        AdviceFitness fitness = AdviceFitness.valueOf(cmd.fitness());
        Prognosis prognosis = Prognosis.valueOf(cmd.prognosis());
        String adjustments = FitnessSupport.functional("adjustments", cmd.adjustments(), 2000);
        String restrictions = FitnessSupport.functional("restrictions", cmd.restrictions(), 2000);
        Set<String> asked = Set.of(r.getQuestionCodes().split(","));
        Map<String, String> answers = new LinkedHashMap<>();
        for (Map.Entry<String, String> a : (cmd.answers() == null ? Map.<String, String>of() : cmd.answers()).entrySet()) {
            if (!asked.contains(a.getKey())) {
                throw ApiException.badRequest("unknown_question", "Answer given for a question that was not asked: " + a.getKey());
            }
            String text = FitnessSupport.functional("answers." + a.getKey(), a.getValue(), MAX_ANSWER);
            if (text != null) {
                answers.put(a.getKey(), text);
            }
        }
        Optional<AdviceReport> current = latest(r);
        AdviceReport rep;
        if (current.isPresent() && DRAFT.equals(current.get().getStatus())) {
            rep = current.get();
        } else {
            rep = AdviceReport.create();
            rep.setRequestId(r.getId());
            rep.setVersion(current.map(x -> x.getVersion() + 1).orElse(1));
        }
        rep.setStatus(DRAFT);
        rep.setFitness(fitness.name());
        rep.setPrognosis(prognosis.name());
        rep.setAdjustments(adjustments);
        rep.setRestrictions(restrictions);
        rep.setExpectedReturn(cmd.expectedReturn());
        rep.setReviewDate(cmd.reviewDate());
        rep.setAnswers(support.json(answers));
        rep.setDraftedBy(user.username());
        rep.setDraftedAt(support.now());
        reports.save(rep);
        r.setStatus(AdviceStatus.REPORT_DRAFTED.name());
        r.setUpdatedAt(support.now());
        audit.record("ADVICE_REPORT_DRAFT", "ADVICE_REQUEST", id.toString(), AuditService.Outcome.SUCCESS, "version=" + rep.getVersion());
        return view(r, Audience.OH);
    }

    @Transactional
    public AdviceRequestView sign(CurrentUser user, UUID id) {
        AdviceRequest r = loadOh(user, id, "ADVICE_REPORT_SIGN");
        AdviceReport rep = latest(r).filter(x -> DRAFT.equals(x.getStatus()))
                .orElseThrow(() -> ApiException.conflict("no_draft", "There is no draft report to sign"));
        if (!AdviceStatus.REPORT_DRAFTED.name().equals(r.getStatus())) {
            throw ApiException.conflict("invalid_state", "The request has no drafted report");
        }
        rep.setSignedBy(user.username());
        rep.setSignedAt(support.now());
        if (support.flag("absence.advice.employee-review-required", true)) {
            rep.setStatus(AdviceStatus.AWAITING_EMPLOYEE.name());
            r.setStatus(AdviceStatus.AWAITING_EMPLOYEE.name());
            support.notifyEmployee(r.getEmployeeId(), "absence.advice-review", Map.of(), "#/advice");
            if (r.getCaseId() != null) {
                timeline.record(r.getCaseId(), "ADVICE_SIGNED", "Advice report " + r.getRef() + " signed – awaiting the employee's review");
            }
        } else {
            release(r, rep, null);
        }
        r.setUpdatedAt(support.now());
        audit.record("ADVICE_REPORT_SIGN", "ADVICE_REQUEST", id.toString(), AuditService.Outcome.SUCCESS, "version=" + rep.getVersion());
        return view(r, Audience.OH);
    }

    // ------------------------------------------------------------------ employee

    @Transactional(readOnly = true)
    public List<EmployeeReport> mine(CurrentUser user) {
        if (user.employeeId() == null) {
            return List.of();
        }
        List<EmployeeReport> out = new ArrayList<>();
        for (AdviceRequest r : requests.findByEmployeeIdOrderByRequestedAtDesc(user.employeeId())) {
            for (AdviceReport rep : reports.findByRequestIdOrderByVersionDesc(r.getId())) {
                if (DRAFT.equals(rep.getStatus()) || SUPERSEDED.equals(rep.getStatus())) {
                    continue;
                }
                out.add(new EmployeeReport(rep.getId(), r.getRef(), r.getRequesterRole(), rep.getStatus(), reportView(rep), rep.getSignedAt()));
            }
        }
        audit.record("ADVICE_REPORTS_SELF_READ", "EMPLOYEE", user.employeeId(), AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    @Transactional
    public EmployeeReport decide(CurrentUser user, UUID reportId, String decision, String comment, String declineReason) {
        AdviceReport rep = reports.findById(reportId).orElseThrow(() -> ApiException.notFound("Advice report"));
        AdviceRequest r = requests.findById(rep.getRequestId())
                .filter(x -> user.employeeId() != null && user.employeeId().equals(x.getEmployeeId()))
                .orElseThrow(() -> ApiException.notFound("Advice report"));
        if (!AdviceStatus.AWAITING_EMPLOYEE.name().equals(rep.getStatus())) {
            throw ApiException.conflict("invalid_state", "This report is not awaiting your decision");
        }
        rep.setEmployeeComment(FitnessSupport.functional("comment", comment, 1000));
        rep.setEmployeeDecision(decision);
        rep.setDecidedAt(support.now());
        if ("CONSENT".equals(decision)) {
            release(r, rep, user);
        } else {
            rep.setDeclineReason(FitnessSupport.clinical("declineReason", declineReason, 1000));
            rep.setStatus(AdviceStatus.WITHHELD.name());
            r.setStatus(AdviceStatus.WITHHELD.name());
            Map<String, String> params = new LinkedHashMap<>();
            params.put("caseRef", r.getCaseRef() == null ? r.getRef() : r.getCaseRef());
            support.notifyRequester(r.getRequesterEmployeeId(), "absence.advice-declined", params, "#/advice-requests");
            if (r.getCaseId() != null) {
                timeline.record(r.getCaseId(), "ADVICE_WITHHELD", "Employee declined the release of advice report " + r.getRef());
            }
        }
        r.setUpdatedAt(support.now());
        audit.record("ADVICE_EMPLOYEE_DECISION", "ADVICE_REQUEST", r.getId().toString(), AuditService.Outcome.SUCCESS, decision);
        return new EmployeeReport(rep.getId(), r.getRef(), r.getRequesterRole(), rep.getStatus(), reportView(rep), rep.getSignedAt());
    }

    /** Releases the signed report: OH_ADVICE_REPORT document, shares (employee + requester, disclosure logged), notification. */
    private void release(AdviceRequest r, AdviceReport rep, CurrentUser employee) {
        Employee e = support.employee(r.getEmployeeId()).orElseThrow(() -> ApiException.notFound("Employee"));
        Map<String, String> answers = support.readStringMap(rep.getAnswers());
        Map<String, String> f = new LinkedHashMap<>();
        f.put("employeeName", e.getDisplayName());
        f.put("employeeId", e.getEmployeeId());
        f.put("requestRef", r.getRef());
        f.put("requestedBy", requesterName(r));
        f.put("fitness", fitnessLabel(rep.getFitness()));
        put(f, "adjustments", rep.getAdjustments());
        put(f, "restrictions", rep.getRestrictions());
        f.put("prognosis", prognosisLabel(rep.getPrognosis()));
        put(f, "expectedReturn", rep.getExpectedReturn() == null ? null : rep.getExpectedReturn().toString());
        put(f, "reviewDate", rep.getReviewDate() == null ? null : rep.getReviewDate().toString());
        put(f, "answers", answers.isEmpty() ? null : String.join(" | ", answers.entrySet().stream()
                .map(a -> QUESTIONS.getOrDefault(a.getKey(), new LibraryQuestion(a.getKey(), a.getKey())).label() + ": " + a.getValue()).toList()));
        put(f, "employeeComment", rep.getEmployeeComment());
        f.put("issuedBy", support.staffName(rep.getSignedBy()));
        String actor = rep.getSignedBy();
        DocumentService.DocumentView doc = documents.generateSigned(actor, "OH_ADVICE_REPORT", e.getEmployeeId(), r.getCaseId(), f);
        documents.shareAsSystem(actor, doc.id(), DocumentShare.Target.EMPLOYEE);
        boolean toManager = "MANAGER".equals(r.getRequesterRole()) && e.getManagerEmployeeId() != null;
        documents.shareAsSystem(actor, doc.id(), toManager ? DocumentShare.Target.MANAGER : DocumentShare.Target.HR);
        rep.setDocumentId(doc.id());
        rep.setStatus(AdviceStatus.RELEASED.name());
        rep.setReleasedAt(support.now());
        r.setStatus(AdviceStatus.RELEASED.name());
        Map<String, String> params = new LinkedHashMap<>();
        params.put("employeeName", e.getDisplayName());
        params.put("caseRef", r.getCaseRef() == null ? r.getRef() : r.getCaseRef());
        support.notifyRequester(toManager ? r.getRequesterEmployeeId() : null, "absence.advice-released", params, "#/advice-requests");
        bus.publish(new E06Events.AdviceReleased(r.getId().toString(), e.getEmployeeId(), doc.id().toString()));
        if (r.getCaseId() != null) {
            timeline.record(r.getCaseId(), "ADVICE_RELEASED", "Advice report " + r.getRef() + " released to the requester"
                    + (employee != null ? " with the employee's consent" : ""));
        }
    }

    private static void put(Map<String, String> f, String key, String value) {
        if (value != null && !value.isBlank()) {
            f.put(key, value);
        }
    }

    private String requesterName(AdviceRequest r) {
        String role = "MANAGER".equals(r.getRequesterRole()) ? "line manager" : "HR";
        return support.employee(r.getRequesterEmployeeId()).map(x -> x.getDisplayName() + " (" + role + ")").orElse(role);
    }

    static String fitnessLabel(String fitness) {
        return switch (AdviceFitness.valueOf(fitness)) {
            case FIT -> "Fit for work";
            case FIT_WITH_ADJUSTMENTS -> "Fit for work with adjustments";
            case TEMPORARILY_UNFIT -> "Temporarily unfit for work";
            case UNFIT_LONG_TERM -> "Unfit for work for the foreseeable future";
        };
    }

    static String prognosisLabel(String prognosis) {
        return switch (Prognosis.valueOf(prognosis)) {
            case RTW_WITHIN_2_WEEKS -> "Return to work expected within 2 weeks";
            case RTW_WITHIN_4_WEEKS -> "Return to work expected within 4 weeks";
            case RTW_WITHIN_12_WEEKS -> "Return to work expected within 12 weeks";
            case UNCERTAIN -> "Timing of return to work uncertain";
            case RTW_UNLIKELY -> "Return to work unlikely in the foreseeable future";
        };
    }

    // ------------------------------------------------------------------ helpers

    enum Audience { OH, REQUESTER, WORKSPACE }

    private AdviceRequest loadOh(CurrentUser user, UUID id, String auditAction) {
        AdviceRequest r = requests.findById(id).orElseThrow(() -> ApiException.notFound("Advice request"));
        support.requireClinicalUpdate(user, r.getEmployeeId(), auditAction);
        return r;
    }

    private Optional<AdviceReport> latest(AdviceRequest r) {
        return reports.findFirstByRequestIdOrderByVersionDesc(r.getId());
    }

    private ReportView reportView(AdviceReport rep) {
        return new ReportView(rep.getFitness(), rep.getAdjustments(), rep.getRestrictions(), rep.getPrognosis(), rep.getExpectedReturn(),
                rep.getReviewDate(), support.readStringMap(rep.getAnswers()), rep.getEmployeeComment(), rep.getDocumentId(), rep.getReleasedAt());
    }

    private AdviceRequestView view(AdviceRequest r, Audience audience) {
        List<AdviceReport> all = reports.findByRequestIdOrderByVersionDesc(r.getId());
        ReportView released = AdviceStatus.RELEASED.name().equals(r.getStatus())
                ? all.stream().filter(x -> AdviceStatus.RELEASED.name().equals(x.getStatus())).findFirst().map(this::reportView).orElse(null)
                : null;
        OhReportView current = audience != Audience.OH || all.isEmpty() ? null : ohReport(all.get(0));
        return new AdviceRequestView(r.getId(), r.getRef(), r.getEmployeeId(), support.name(r.getEmployeeId()), r.getStatus(),
                List.of(r.getQuestionCodes().split(",")), r.getRequestedBy(), r.getRequesterRole(), r.getRequestedAt(), r.getSlaDueAt(),
                r.getCaseRef(), audience == Audience.OH ? r.getCaseId() : null, r.getEpisodeId(),
                audience == Audience.WORKSPACE ? null : r.getContext(), released, current, audience == Audience.OH ? r.getAcceptedBy() : null);
    }

    private OhReportView ohReport(AdviceReport x) {
        return new OhReportView(x.getId(), x.getVersion(), x.getStatus(), x.getFitness(), x.getAdjustments(), x.getRestrictions(), x.getPrognosis(),
                x.getExpectedReturn(), x.getReviewDate(), support.readStringMap(x.getAnswers()), x.getDraftedBy(), x.getDraftedAt(), x.getSignedBy(),
                x.getSignedAt(), x.getEmployeeDecision(), x.getEmployeeComment(), x.getDeclineReason(), x.getDecidedAt(), x.getDocumentId(),
                x.getReleasedAt());
    }
}
