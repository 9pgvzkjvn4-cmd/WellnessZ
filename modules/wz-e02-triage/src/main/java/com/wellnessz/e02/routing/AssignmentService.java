package com.wellnessz.e02.routing;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.model.CaseTask;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OhCaseRepository;
import com.wellnessz.casekernel.module.ModuleRegistry;
import com.wellnessz.casekernel.support.CaseAccess;
import com.wellnessz.casekernel.support.CaseTimeline;
import com.wellnessz.casekernel.support.CaseWorkService;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.events.IntegrationEnvelope;
import com.wellnessz.common.model.Roles;
import com.wellnessz.e02.E02Events;
import com.wellnessz.e02.model.CaseAssignment;
import com.wellnessz.e02.model.CaseAssignmentRepository;
import com.wellnessz.e02.model.Handover;
import com.wellnessz.e02.model.HandoverRepository;
import com.wellnessz.e02.sla.SlaService;
import com.wellnessz.integration.idempotency.IdempotentConsumer;
import com.wellnessz.integration.outbox.EventBus;
import com.wellnessz.masterdata.StaffMember;
import com.wellnessz.masterdata.StaffMemberRepository;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Routing on case creation, manual (re)assignment with history, caseload view, availability / cover and
 * structured shift handover (E02-F02/F03, E02-BL-019).
 */
@Service
public class AssignmentService {

    private final AssignmentEngine engine;
    private final OhCaseRepository cases;
    private final CaseAssignmentRepository history;
    private final HandoverRepository handovers;
    private final StaffMemberRepository staff;
    private final CaseAccess access;
    private final CaseTimeline timeline;
    private final CaseWorkService work;
    private final SlaService sla;
    private final EventBus bus;
    private final IdempotentConsumer consumer;
    private final ModuleRegistry modules;
    private final AuditService audit;
    private final JsonMapper json;
    private final Clock clock;

    public AssignmentService(AssignmentEngine engine, OhCaseRepository cases, CaseAssignmentRepository history,
                             HandoverRepository handovers, StaffMemberRepository staff, CaseAccess access, CaseTimeline timeline,
                             CaseWorkService work, SlaService sla, EventBus bus, IdempotentConsumer consumer,
                             ModuleRegistry modules, AuditService audit, JsonMapper json, Clock clock) {
        this.engine = engine;
        this.cases = cases;
        this.history = history;
        this.handovers = handovers;
        this.staff = staff;
        this.access = access;
        this.timeline = timeline;
        this.work = work;
        this.sla = sla;
        this.bus = bus;
        this.consumer = consumer;
        this.modules = modules;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    /** New case → safety routing / rules → owner, and the triage SLA starts (E01-BL-023). */
    @EventListener(condition = "#envelope.eventType() == 'case.opened.v1'")
    public void onCaseOpened(IntegrationEnvelope envelope) {
        if (!modules.isEnabled("E02")) {
            return;
        }
        consumer.handleOnce("e02.routing", envelope, () -> {
            JsonNode n = json.readTree(envelope.payload());
            OhCase c = cases.findById(UUID.fromString(n.get("caseId").asString())).orElse(null);
            if (c == null) {
                return;
            }
            AssignmentEngine.Decision d = engine.decide(c);
            if (d.minimumUrgency() != null) {
                c.raiseUrgencyTo(d.minimumUrgency(), clock.instant());
            }
            apply(c, d.queue(), d.username(), d.explanation(), "routing");
            sla.start(c, SlaService.TRIAGE);
        });
    }

    @Transactional
    public CaseAssignment assign(CurrentUser user, UUID caseId, String toUsername, String queueCode, String reason) {
        OhCase c = access.clinical(user, caseId, AccessPolicy.Action.UPDATE, "CASE_ASSIGN");
        if (toUsername == null && queueCode == null) {
            toUsername = user.username(); // "assign to me"
        }
        boolean self = toUsername != null && toUsername.equals(user.username());
        if (!user.hasAnyRole(Roles.NURSING_HEAD, Roles.OH_ADMIN) && !self && !user.hasAnyRole(Roles.OH_TEAM)) {
            throw ApiException.forbidden("Not permitted to assign cases");
        }
        if (toUsername != null) {
            StaffMember s = staff.findById(toUsername).filter(StaffMember::isActive)
                    .orElseThrow(() -> ApiException.badRequest("unknown_user", "Unknown or inactive staff member"));
            if (!s.isAvailableOn(LocalDate.now(clock)) && !user.hasRole(Roles.NURSING_HEAD)) {
                throw ApiException.conflict("out_of_office", "Staff member is out of office; assign to the cover colleague");
            }
        }
        String queue = queueCode != null ? queueCode : c.getQueueCode();
        if (queue != null && engine.queue(queue).isEmpty()) {
            throw ApiException.badRequest("unknown_queue", "Unknown queue");
        }
        CaseAssignment a = apply(c, queue, toUsername, reason, user.username());
        audit.record("CASE_ASSIGN", "CASE", caseId.toString(), AuditService.Outcome.SUCCESS, toUsername);
        return a;
    }

    CaseAssignment apply(OhCase c, String queue, String owner, String reason, String actor) {
        CaseAssignment a = new CaseAssignment();
        a.setId(UUID.randomUUID());
        a.setCaseId(c.getId());
        a.setFromUsername(c.getOwnerUsername());
        a.setToUsername(owner);
        a.setQueueCode(queue);
        a.setReason(reason == null ? null : reason.substring(0, Math.min(reason.length(), 500)));
        a.setActor(actor);
        a.setAssignedAt(clock.instant());
        history.save(a);
        c.route(queue, owner, clock.instant());
        timeline.record(c.getId(), "ASSIGNED", "Queue " + queue + (owner == null ? ", unassigned" : ", owner " + owner));
        bus.publish(new E02Events.CaseAssigned(c.getId().toString(), c.getCaseRef(), queue, owner));
        return a;
    }

    public List<CaseAssignment> history(CurrentUser user, UUID caseId) {
        access.clinical(user, caseId, AccessPolicy.Action.READ, "CASE_ASSIGNMENTS_READ");
        return history.findByCaseIdOrderByAssignedAtDesc(caseId);
    }

    public record CaseloadRow(String username, String displayName, List<String> roles, int caseloadCap, long openCases,
                              long urgentCases, LocalDate outOfOfficeUntil, String coverUsername, int utilisationPct) {
    }

    public List<CaseloadRow> caseload(Map<String, Long> urgent) {
        Map<String, Long> load = engine.openCaseload();
        return staff.findByActiveTrueOrderByDisplayName().stream().filter(s -> s.hasRole(Roles.OH_CLINICIAN) || s.hasRole(Roles.OH_PHYSICIAN)
                        || s.hasRole(Roles.AME) || s.hasRole(Roles.PSYCH_CLINICIAN) || s.hasRole(Roles.CMO))
                .map(s -> {
                    long open = load.getOrDefault(s.getUsername(), 0L);
                    return new CaseloadRow(s.getUsername(), s.getDisplayName(), StaffMember.list(s.getRoles()), s.getCaseloadCap(), open,
                            urgent.getOrDefault(s.getUsername(), 0L), s.getOutOfOfficeUntil(), s.getCoverUsername(),
                            s.getCaseloadCap() == 0 ? 0 : (int) Math.round(100.0 * open / s.getCaseloadCap()));
                }).toList();
    }

    @Transactional
    public StaffMember availability(CurrentUser user, String username, LocalDate until, String cover) {
        if (!user.username().equals(username) && !user.hasAnyRole(Roles.NURSING_HEAD, Roles.OH_ADMIN)) {
            throw ApiException.forbidden("Only the staff member or the Nursing Head can change availability");
        }
        StaffMember s = staff.findById(username).orElseThrow(() -> ApiException.notFound("Staff member"));
        if (cover != null && (cover.equals(username) || staff.findById(cover).isEmpty())) {
            throw ApiException.badRequest("invalid_cover", "Cover must be another active staff member");
        }
        s.setAvailability(until, cover);
        audit.record("STAFF_AVAILABILITY", "STAFF", username, AuditService.Outcome.SUCCESS, null);
        return s;
    }

    @Transactional
    public Handover handover(CurrentUser user, UUID caseId, String to, String summary, String outstanding, Instant nextContact) {
        OhCase c = access.clinical(user, caseId, AccessPolicy.Action.UPDATE, "CASE_HANDOVER");
        staff.findById(to).filter(StaffMember::isActive).orElseThrow(() -> ApiException.badRequest("unknown_user", "Unknown staff member"));
        Handover h = new Handover();
        h.setId(UUID.randomUUID());
        h.setCaseId(caseId);
        h.setFromUsername(user.username());
        h.setToUsername(to);
        h.setSummary(summary);
        h.setOutstandingActions(outstanding);
        h.setNextContactAt(nextContact);
        h.setCreatedAt(clock.instant());
        handovers.save(h);
        work.systemTask(caseId, CaseTask.Type.HANDOVER, "Acknowledge handover from " + user.username(), nextContact, to, user.username());
        apply(c, c.getQueueCode(), to, "Shift handover", user.username());
        audit.record("CASE_HANDOVER", "CASE", caseId.toString(), AuditService.Outcome.SUCCESS, to);
        return h;
    }

    @Transactional
    public Handover acknowledge(CurrentUser user, UUID handoverId) {
        Handover h = handovers.findById(handoverId).orElseThrow(() -> ApiException.notFound("Handover"));
        if (!h.getToUsername().equals(user.username())) {
            throw ApiException.forbidden("Only the receiving clinician can acknowledge");
        }
        h.setAcknowledgedAt(clock.instant());
        timeline.record(h.getCaseId(), "HANDOVER_ACK", "Handover acknowledged by " + user.username());
        return h;
    }

    public List<Handover> pending(CurrentUser user) {
        return handovers.findByToUsernameAndAcknowledgedAtIsNullOrderByCreatedAt(user.username());
    }
}
