package com.wellnessz.e02.escalation;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OhCaseRepository;
import com.wellnessz.casekernel.support.CaseAccess;
import com.wellnessz.casekernel.support.CaseTimeline;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.e02.E02Events;
import com.wellnessz.e02.model.CaseConference;
import com.wellnessz.e02.model.CaseConferenceRepository;
import com.wellnessz.e02.model.Escalation;
import com.wellnessz.e02.model.EscalationRepository;
import com.wellnessz.e02.sla.SlaService;
import com.wellnessz.integration.outbox.EventBus;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

import tools.jackson.databind.json.JsonMapper;

/**
 * Escalation & second opinion (E02-F05): reasoned escalation with response SLA, acknowledgement, decision captured on
 * the timeline, and case conferences with role-limited minutes.
 */
@Service
public class EscalationService {

    static final Map<String, Integer> RESPONSE_MINUTES = Map.of("PHYSICIAN", 240, "CMO", 480, "EXTERNAL_SPECIALIST", 2880,
            "EMERGENCY_SERVICES", 0);
    static final Set<String> REASONS = Set.of("COMPLEX_CLINICAL", "MEDICO_LEGAL", "CREW_FITNESS", "SAFETY_CRITICAL",
            "MENTAL_HEALTH_RISK", "SECOND_OPINION", "OTHER");

    private final EscalationRepository escalations;
    private final CaseConferenceRepository conferences;
    private final OhCaseRepository cases;
    private final CaseAccess access;
    private final CaseTimeline timeline;
    private final SlaService sla;
    private final EventBus bus;
    private final AuditService audit;
    private final JsonMapper json;
    private final Clock clock;

    public EscalationService(EscalationRepository escalations, CaseConferenceRepository conferences, OhCaseRepository cases,
                             CaseAccess access, CaseTimeline timeline, SlaService sla, EventBus bus, AuditService audit,
                             JsonMapper json, Clock clock) {
        this.escalations = escalations;
        this.conferences = conferences;
        this.cases = cases;
        this.access = access;
        this.timeline = timeline;
        this.sla = sla;
        this.bus = bus;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    public record EscalationView(UUID id, UUID caseId, String caseRef, String level, String reasonCode, String note, String status,
                                 String raisedBy, Instant raisedAt, Instant dueAt, SlaService.SlaView sla, String acknowledgedBy,
                                 Instant acknowledgedAt, String decision, String decidedBy, Instant decidedAt) {
    }

    @Transactional
    public EscalationView raise(CurrentUser user, UUID caseId, String level, String reasonCode, String note) {
        OhCase c = access.clinical(user, caseId, AccessPolicy.Action.UPDATE, "CASE_ESCALATE");
        if (!RESPONSE_MINUTES.containsKey(level) || !REASONS.contains(reasonCode)) {
            throw ApiException.badRequest("invalid_escalation", "Unknown escalation level or reason");
        }
        Instant now = clock.instant();
        Escalation e = new Escalation();
        e.setId(UUID.randomUUID());
        e.setCaseId(caseId);
        e.setLevel(level);
        e.setReasonCode(reasonCode);
        e.setNote(note);
        e.setRequestedBy(user.username());
        e.setRequestedAt(now);
        e.setDueAt(now.plusSeconds(RESPONSE_MINUTES.get(level) * 60L));
        e.setStatus("OPEN");
        escalations.save(e);
        c.setEscalated(true, now);
        if (RESPONSE_MINUTES.get(level) > 0) {
            sla.start(caseId, SlaService.ESCALATION_RESPONSE, c.getUrgency().name(), null, RESPONSE_MINUTES.get(level));
        }
        timeline.record(caseId, "ESCALATED", "Escalated to " + level + " (" + reasonCode + ")");
        bus.publish(new E02Events.CaseEscalated(caseId.toString(), c.getCaseRef(), level, reasonCode));
        audit.record("CASE_ESCALATE", "CASE", caseId.toString(), AuditService.Outcome.SUCCESS, level);
        return view(e, c.getCaseRef());
    }

    @Transactional(readOnly = true)
    public List<EscalationView> list(CurrentUser user, UUID caseId, String status, boolean mine) {
        List<Escalation> rows;
        if (caseId != null) {
            access.clinical(user, caseId, AccessPolicy.Action.READ, "CASE_ESCALATIONS_READ");
            rows = escalations.findByCaseIdOrderByRequestedAtDesc(caseId);
        } else {
            rows = escalations.findByStatusInOrderByDueAtAsc(status == null ? List.of("OPEN", "ACKNOWLEDGED", "DECIDED") : List.of(status));
        }
        return rows.stream()
                .filter(e -> status == null || status.equals(e.getStatus()))
                .filter(e -> !mine || respondsTo(user, e.getLevel()) || user.username().equals(e.getRequestedBy()))
                .map(e -> cases.findById(e.getCaseId()).filter(c -> access.decide(user, c).permitted()).map(c -> view(e, c.getCaseRef())).orElse(null))
                .filter(java.util.Objects::nonNull)
                .limit(500).toList();
    }

    @Transactional
    public EscalationView acknowledge(CurrentUser user, UUID id) {
        Escalation e = load(user, id);
        e.setStatus("ACKNOWLEDGED");
        e.setAcknowledgedBy(user.username());
        e.setAcknowledgedAt(clock.instant());
        sla.stop(e.getCaseId(), SlaService.ESCALATION_RESPONSE);
        timeline.record(e.getCaseId(), "ESCALATION_ACK", "Escalation acknowledged by " + user.username());
        return view(e, ref(e));
    }

    @Transactional
    public EscalationView decide(CurrentUser user, UUID id, String decision) {
        Escalation e = load(user, id);
        if (e.getAcknowledgedAt() == null) {
            e.setAcknowledgedBy(user.username());
            e.setAcknowledgedAt(clock.instant());
            sla.stop(e.getCaseId(), SlaService.ESCALATION_RESPONSE);
        }
        e.setDecision(decision);
        e.setDecidedBy(user.username());
        e.setDecidedAt(clock.instant());
        e.setStatus("DECIDED");
        if (escalations.countByCaseIdAndStatusIn(e.getCaseId(), List.of("OPEN", "ACKNOWLEDGED")) <= 1) {
            cases.findById(e.getCaseId()).ifPresent(c -> c.setEscalated(false, clock.instant()));
        }
        timeline.record(e.getCaseId(), "ESCALATION_DECIDED", "Escalation decided by " + user.username());
        audit.record("ESCALATION_DECISION", "CASE", e.getCaseId().toString(), AuditService.Outcome.SUCCESS, e.getLevel());
        return view(e, ref(e));
    }

    private Escalation load(CurrentUser user, UUID id) {
        Escalation e = escalations.findById(id).orElseThrow(() -> ApiException.notFound("Escalation"));
        if (!respondsTo(user, e.getLevel())) {
            throw ApiException.forbidden("Escalation is addressed to " + e.getLevel());
        }
        access.clinical(user, e.getCaseId(), AccessPolicy.Action.UPDATE, "ESCALATION_RESPOND");
        return e;
    }

    static boolean respondsTo(CurrentUser user, String level) {
        return switch (level) {
            case "CMO" -> user.hasRole(Roles.CMO);
            case "PHYSICIAN" -> user.hasAnyRole(Roles.OH_PHYSICIAN, Roles.AME, Roles.CMO);
            default -> user.hasAnyRole(Roles.OH_PHYSICIAN, Roles.AME, Roles.CMO, Roles.NURSING_HEAD);
        };
    }

    private String ref(Escalation e) {
        return cases.findById(e.getCaseId()).map(OhCase::getCaseRef).orElse(null);
    }

    private EscalationView view(Escalation e, String caseRef) {
        SlaService.SlaView s = "OPEN".equals(e.getStatus()) ? SlaService.view(timerLike(e), clock.instant()) : null;
        return new EscalationView(e.getId(), e.getCaseId(), caseRef, e.getLevel(), e.getReasonCode(), e.getNote(), e.getStatus(),
                e.getRequestedBy(), e.getRequestedAt(), e.getDueAt(), s, e.getAcknowledgedBy(), e.getAcknowledgedAt(), e.getDecision(),
                e.getDecidedBy(), e.getDecidedAt());
    }

    private static com.wellnessz.e02.model.SlaTimer timerLike(Escalation e) {
        var t = new com.wellnessz.e02.model.SlaTimer();
        t.setMilestone(SlaService.ESCALATION_RESPONSE);
        t.setStatus("RUNNING");
        t.setStartedAt(e.getRequestedAt());
        t.setDueAt(e.getDueAt());
        long total = java.time.Duration.between(e.getRequestedAt(), e.getDueAt()).toSeconds();
        t.setAmberAt(e.getRequestedAt().plusSeconds(total * 3 / 4));
        return t;
    }

    // ------------------------------------------------------------ conferences (E02-BL-012)

    public record Attendee(String name, String role) {
    }

    public record ConferenceView(UUID id, UUID caseId, Instant scheduledAt, List<Attendee> attendees, String agenda, String minutes,
                                 String decisions, String visibility) {
    }

    @Transactional
    public ConferenceView schedule(CurrentUser user, UUID caseId, Instant at, List<Attendee> attendees, String agenda) {
        access.clinical(user, caseId, AccessPolicy.Action.UPDATE, "CASE_CONFERENCE");
        CaseConference cc = new CaseConference();
        cc.setId(UUID.randomUUID());
        cc.setCaseId(caseId);
        cc.setScheduledAt(at);
        cc.setAttendees(json.writeValueAsString(attendees == null ? List.of() : attendees));
        cc.setAgenda(agenda);
        cc.setVisibility("CLINICAL_ONLY");
        cc.setCreatedBy(user.username());
        cc.setCreatedAt(clock.instant());
        conferences.save(cc);
        timeline.record(caseId, "CONFERENCE_SCHEDULED", "Case conference scheduled for " + at);
        return view(cc, true);
    }

    @Transactional
    public ConferenceView minutes(CurrentUser user, UUID conferenceId, String minutes, String decisions, String visibility) {
        CaseConference cc = conferences.findById(conferenceId).orElseThrow(() -> ApiException.notFound("Conference"));
        access.clinical(user, cc.getCaseId(), AccessPolicy.Action.UPDATE, "CASE_CONFERENCE_MINUTES");
        if (!Set.of("CLINICAL_ONLY", "ALL_ATTENDEES").contains(visibility)) {
            throw ApiException.badRequest("invalid_visibility", "Unknown visibility");
        }
        cc.setMinutes(minutes);
        cc.setDecisions(decisions);
        cc.setVisibility(visibility);
        timeline.record(cc.getCaseId(), "CONFERENCE_MINUTES", "Case conference minutes recorded");
        audit.record("CASE_CONFERENCE_MINUTES", "CASE", cc.getCaseId().toString(), AuditService.Outcome.SUCCESS, visibility);
        return view(cc, true);
    }

    @Transactional(readOnly = true)
    public List<ConferenceView> conferences(CurrentUser user, UUID caseId) {
        access.clinical(user, caseId, AccessPolicy.Action.READ, "CASE_CONFERENCE_READ");
        return conferences.findByCaseIdOrderByScheduledAtDesc(caseId).stream().map(c -> view(c, true)).toList();
    }

    private ConferenceView view(CaseConference c, boolean clinical) {
        List<Attendee> attendees = List.of(json.readValue(c.getAttendees(), Attendee[].class));
        boolean showMinutes = clinical || "ALL_ATTENDEES".equals(c.getVisibility());
        return new ConferenceView(c.getId(), c.getCaseId(), c.getScheduledAt(), attendees, c.getAgenda(),
                showMinutes ? c.getMinutes() : null, showMinutes ? c.getDecisions() : null, c.getVisibility());
    }
}
