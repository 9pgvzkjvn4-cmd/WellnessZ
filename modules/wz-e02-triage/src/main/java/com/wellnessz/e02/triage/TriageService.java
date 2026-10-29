package com.wellnessz.e02.triage;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.model.CaseStatus;
import com.wellnessz.casekernel.model.CaseTask;
import com.wellnessz.casekernel.model.CaseType;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.Urgency;
import com.wellnessz.casekernel.support.CaseAccess;
import com.wellnessz.casekernel.support.CaseTimeline;
import com.wellnessz.casekernel.support.CaseTransitionGuard;
import com.wellnessz.casekernel.support.CaseWorkService;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.e02.E02Events;
import com.wellnessz.e02.complexity.ComplexityService;
import com.wellnessz.e02.model.CaseAdvice;
import com.wellnessz.e02.model.CaseAdviceRepository;
import com.wellnessz.e02.model.TriageAssessment;
import com.wellnessz.e02.model.TriageAssessmentRepository;
import com.wellnessz.e02.model.TriageProtocol;
import com.wellnessz.e02.model.TriageProtocolRepository;
import com.wellnessz.e02.sla.SlaService;
import com.wellnessz.integration.outbox.EventBus;
import com.wellnessz.masterdata.EmployeeRepository;
import com.wellnessz.notification.Channel;
import com.wellnessz.notification.NotificationService;
import com.wellnessz.notification.Priority;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Nurse triage (E02-F01 / F07): server-side protocol evaluation, red-flag hard stops, override with reason, protocol
 * version stored on the assessment, telephone call log with consent and call-back task, advice release to the portal.
 */
@Service
public class TriageService {

    static final Set<String> TRIAGERS = Set.of(Roles.OH_CLINICIAN, Roles.OH_PHYSICIAN, Roles.AME, Roles.CMO, Roles.NURSING_HEAD,
            Roles.PSYCH_CLINICIAN);

    private final TriageProtocolRepository protocols;
    private final TriageAssessmentRepository assessments;
    private final CaseAdviceRepository adviceRepo;
    private final ProtocolCatalog catalog;
    private final CaseAccess access;
    private final CaseTimeline timeline;
    private final CaseWorkService work;
    private final List<CaseTransitionGuard> guards;
    private final SlaService sla;
    private final ComplexityService complexity;
    private final EventBus bus;
    private final NotificationService notifications;
    private final EmployeeRepository employees;
    private final AuditService audit;
    private final JsonMapper json;
    private final Clock clock;

    public TriageService(TriageProtocolRepository protocols, TriageAssessmentRepository assessments, CaseAdviceRepository adviceRepo,
                         ProtocolCatalog catalog, CaseAccess access, CaseTimeline timeline, CaseWorkService work,
                         List<CaseTransitionGuard> guards, SlaService sla, ComplexityService complexity, EventBus bus,
                         NotificationService notifications, EmployeeRepository employees, AuditService audit, JsonMapper json,
                         Clock clock) {
        this.protocols = protocols;
        this.assessments = assessments;
        this.adviceRepo = adviceRepo;
        this.catalog = catalog;
        this.access = access;
        this.timeline = timeline;
        this.work = work;
        this.guards = guards;
        this.sla = sla;
        this.complexity = complexity;
        this.bus = bus;
        this.notifications = notifications;
        this.employees = employees;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    public record CallLog(String caller, String phone, Instant startedAt, Instant endedAt, boolean consentToRemoteAssessment,
                          Instant callBackAt) {
    }

    public record TriageRequest(String protocolCode, Integer protocolVersion, String mode, Map<String, Object> answers,
                                Urgency overrideUrgency, String overrideReason, CallLog call) {
    }

    public record TriageResult(UUID assessmentId, Urgency computedUrgency, Urgency finalUrgency, List<String> redFlagsTriggered,
                               String disposition, String adviceKey, int protocolVersion, String protocolCode, CaseType caseType,
                               String queueCode, String ownerUsername, SlaService.SlaView sla, UUID callBackTaskId,
                               CaseStatus status, Instant assessedAt, String assessedBy) {
    }

    /** Latest APPROVED version, else latest DRAFT (drafts usable in test environments; the UI shows the status). */
    public TriageProtocol current(String code) {
        List<TriageProtocol> versions = protocols.findByCodeOrderByVersionDesc(code);
        return versions.stream().filter(p -> "APPROVED".equals(p.getStatus())).findFirst()
                .or(() -> versions.stream().filter(p -> "DRAFT".equals(p.getStatus())).findFirst())
                .orElseThrow(() -> ApiException.notFound("Protocol"));
    }

    @Transactional
    public TriageResult triage(CurrentUser user, UUID caseId, TriageRequest r) {
        if (!user.hasAnyRole(TRIAGERS.toArray(String[]::new))) {
            throw ApiException.forbidden("Not permitted to triage");
        }
        OhCase c = access.clinical(user, caseId, AccessPolicy.Action.UPDATE, "CASE_TRIAGE");
        if (!c.isOpen()) {
            throw ApiException.conflict("case_closed", "Case is not open");
        }
        TriageProtocol p = r.protocolVersion() == null ? current(r.protocolCode())
                : protocols.findByCodeOrderByVersionDesc(r.protocolCode()).stream().filter(x -> x.getVersion() == r.protocolVersion())
                    .findFirst().orElseThrow(() -> ApiException.notFound("Protocol version"));
        String mode = r.mode() == null ? "IN_PERSON" : r.mode();
        if ("TELEPHONE".equals(mode) && (r.call() == null || !r.call().consentToRemoteAssessment())) {
            throw ApiException.badRequest("consent_required", "Record the caller's consent to remote assessment");
        }
        JsonNode def = json.readTree(p.getDefinition());
        ProtocolEvaluator.Result eval;
        try {
            eval = ProtocolEvaluator.evaluate(def, r.answers());
        } catch (ProtocolEvaluator.IncompleteException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "triage_incomplete", e.getMessage());
        }
        Urgency fin = eval.urgency();
        if (r.overrideUrgency() != null && r.overrideUrgency() != eval.urgency()) {
            if (r.overrideReason() == null || r.overrideReason().isBlank()) {
                throw ApiException.badRequest("override_reason_required", "A reason is required to override the computed urgency");
            }
            if (!eval.redFlags().isEmpty() && r.overrideUrgency() != Urgency.EMERGENCY) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "red_flag_hard_stop",
                        "A red flag was triggered – urgency cannot be lowered below EMERGENCY");
            }
            fin = r.overrideUrgency();
        }
        if (c.isSafetyCritical()) {
            fin = Urgency.max(fin, Urgency.SEMI_URGENT); // safety-critical roles never wait in a routine queue
        }
        JsonNode outcome = def.path("outcomes").path(fin.name());
        String disposition = outcome.path("disposition").asString(null);
        String adviceKey = outcome.path("adviceKey").asString(null);
        CaseType type = def.has("caseType") ? CaseType.valueOf(def.get("caseType").asString()) : null;
        Instant now = clock.instant();
        TriageAssessment a = new TriageAssessment();
        a.setId(UUID.randomUUID());
        a.setCaseId(caseId);
        a.setProtocolCode(p.getCode());
        a.setProtocolVersion(p.getVersion());
        a.setMode(mode);
        a.setAnswers(json.writeValueAsString(r.answers() == null ? Map.of() : r.answers()));
        a.setRedFlags(String.join(",", eval.redFlags()));
        a.setComputedUrgency(eval.urgency().name());
        a.setFinalUrgency(fin.name());
        a.setDisposition(disposition);
        a.setAdviceKey(adviceKey);
        a.setOverrideReason(r.overrideReason());
        a.setCallLog(r.call() == null ? null : json.writeValueAsString(r.call()));
        a.setAssessedBy(user.username());
        a.setAssessedAt(now);
        assessments.save(a);
        c.triage(fin, type, now);
        sla.stop(caseId, SlaService.TRIAGE);
        UUID callBack = null;
        if (r.call() != null && r.call().callBackAt() != null) {
            callBack = work.systemTask(caseId, CaseTask.Type.CALL_BACK, "Call back " + (r.call().caller() == null ? "employee" : "caller"),
                    r.call().callBackAt(), c.getOwnerUsername() == null ? user.username() : c.getOwnerUsername(), user.username()).getId();
        }
        if (c.getStatus() == CaseStatus.INTAKE) {
            try {
                guards.forEach(g -> g.check(c, CaseStatus.TRIAGE));
                c.transition(CaseStatus.TRIAGE, now);
            } catch (ApiException e) {
                timeline.record(c.getId(), "STATUS_HELD", "Remains in Intake: " + e.getMessage());
            }
        }
        timeline.record(c.getId(), "TRIAGED", "Triaged " + fin + " via " + p.getCode() + " v" + p.getVersion() + " (" + mode + ")"
                + (fin != eval.urgency() ? ", override from " + eval.urgency() : ""));
        bus.publish(new E02Events.CaseTriaged(c.getId().toString(), c.getCaseRef(), fin.name(), c.getCaseType().name()));
        complexity.recalculate(c);
        audit.record("CASE_TRIAGE", "CASE", caseId.toString(), AuditService.Outcome.SUCCESS, fin.name());
        return new TriageResult(a.getId(), eval.urgency(), fin, eval.redFlags(), disposition, adviceKey, p.getVersion(), p.getCode(),
                c.getCaseType(), c.getQueueCode(), c.getOwnerUsername(), sla.view(caseId), callBack, c.getStatus(), now, user.username());
    }

    @Transactional(readOnly = true)
    public List<TriageResult> history(CurrentUser user, UUID caseId) {
        OhCase c = access.clinical(user, caseId, AccessPolicy.Action.READ, "CASE_TRIAGE_READ");
        return assessments.findByCaseIdOrderByAssessedAtDesc(caseId).stream().map(a -> new TriageResult(a.getId(),
                Urgency.valueOf(a.getComputedUrgency()), Urgency.valueOf(a.getFinalUrgency()),
                a.getRedFlags() == null || a.getRedFlags().isBlank() ? List.of() : List.of(a.getRedFlags().split(",")),
                a.getDisposition(), a.getAdviceKey(), a.getProtocolVersion(), a.getProtocolCode(), c.getCaseType(), c.getQueueCode(),
                c.getOwnerUsername(), null, null, c.getStatus(), a.getAssessedAt(), a.getAssessedBy())).toList();
    }

    @Transactional
    public TriageProtocol approve(CurrentUser user, String code, Integer version) {
        if (!user.hasRole(Roles.CMO)) {
            throw ApiException.forbidden("Only the CMO approves triage protocols");
        }
        List<TriageProtocol> versions = protocols.findByCodeOrderByVersionDesc(code);
        TriageProtocol target = versions.stream().filter(p -> version == null ? "DRAFT".equals(p.getStatus()) : p.getVersion() == version)
                .findFirst().orElseThrow(() -> ApiException.notFound("Protocol version"));
        versions.stream().filter(p -> "APPROVED".equals(p.getStatus()) && p != target).forEach(p -> p.setStatus("RETIRED"));
        target.setStatus("APPROVED");
        target.setApprovedBy(user.username());
        target.setApprovedAt(clock.instant());
        audit.record("PROTOCOL_APPROVE", "TRIAGE_PROTOCOL", code + ":" + target.getVersion(), AuditService.Outcome.SUCCESS, null);
        return target;
    }

    public record AdviceView(UUID id, String caseRef, String title, String text, Instant sentAt) {
    }

    @Transactional
    public AdviceView sendAdvice(CurrentUser user, UUID caseId, String adviceKey) {
        OhCase c = access.clinical(user, caseId, AccessPolicy.Action.UPDATE, "CASE_ADVICE");
        if (!catalog.hasAdvice(adviceKey)) {
            throw ApiException.badRequest("unknown_advice", "Unknown advice key");
        }
        CaseAdvice a = new CaseAdvice();
        a.setId(UUID.randomUUID());
        a.setCaseId(caseId);
        a.setEmployeeId(c.getEmployeeId());
        a.setAdviceKey(adviceKey);
        a.setLanguage(c.getLanguage());
        a.setSentBy(user.username());
        a.setSentAt(clock.instant());
        adviceRepo.save(a);
        employees.findById(c.getEmployeeId()).filter(e -> e.getEmail() != null).ifPresent(e -> notifications.enqueue(e.getEmployeeId(),
                e.getEmail(), Channel.EMAIL, Priority.NORMAL, "advice.available",
                Map.of("firstName", e.getDisplayName().split(" ")[0], "caseRef", c.getCaseRef(), "portalLink", "the employee portal"),
                java.time.ZoneId.of("UTC")));
        timeline.record(caseId, "ADVICE_SENT", "Advice released to the employee portal");
        audit.record("CASE_ADVICE", "CASE", caseId.toString(), AuditService.Outcome.SUCCESS, adviceKey);
        return new AdviceView(a.getId(), c.getCaseRef(), adviceKey, catalog.advice(adviceKey, c.getLanguage()), a.getSentAt());
    }

    @Transactional(readOnly = true)
    public List<AdviceView> myAdvice(CurrentUser user, java.util.function.Function<UUID, String> caseRef) {
        if (user.employeeId() == null) {
            return List.of();
        }
        return adviceRepo.findByEmployeeIdOrderBySentAtDesc(user.employeeId()).stream().map(a -> new AdviceView(a.getId(),
                caseRef.apply(a.getCaseId()), "Advice from Occupational Health", catalog.advice(a.getAdviceKey(), a.getLanguage()),
                a.getSentAt())).toList();
    }

    public String adviceText(String key, String lang) {
        String t = catalog.advice(key, lang);
        if (t == null) {
            throw ApiException.notFound("Advice");
        }
        return t;
    }

}
