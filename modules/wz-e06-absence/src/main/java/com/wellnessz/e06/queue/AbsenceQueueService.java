package com.wellnessz.e06.queue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.CaseService;
import com.wellnessz.casekernel.model.CaseCategory;
import com.wellnessz.casekernel.model.CaseLink;
import com.wellnessz.casekernel.model.CaseSource;
import com.wellnessz.casekernel.model.CaseType;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OhCaseRepository;
import com.wellnessz.casekernel.model.OpenCaseCommand;
import com.wellnessz.casekernel.model.Urgency;
import com.wellnessz.casekernel.support.CaseTimeline;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e05.support.FitnessSupport;
import com.wellnessz.e06.E06Events;
import com.wellnessz.e06.episode.EpisodeViews;
import com.wellnessz.e06.lta.LtaService;
import com.wellnessz.e06.model.AbsenceEnums.QueueStatus;
import com.wellnessz.e06.model.Contact;
import com.wellnessz.e06.model.AbsenceContactRepository;
import com.wellnessz.e06.model.E06EpisodeRepository;
import com.wellnessz.e06.model.QueueItem;
import com.wellnessz.e06.model.AbsenceQueueItemRepository;
import com.wellnessz.e06.support.AbsenceSupport;
import com.wellnessz.integration.outbox.EventBus;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Early-intervention queue (E06-F02): OH accepts an item (assigns it, opens / links the kernel ABSENCE case and – when
 * {@code absence.notify-hr-on-engagement} – tells the line manager and HR that OH is engaged, status only), logs contacts
 * (the first REACHED contact is the trigger-to-contact KPI) and closes it. AME_REVIEW items form the AME queue.
 */
@Service
public class AbsenceQueueService {

    private final AbsenceQueueItemRepository queue;
    private final AbsenceContactRepository contacts;
    private final E06EpisodeRepository episodes;
    private final EpisodeViews episodeViews;
    private final OhCaseRepository caseRepo;
    private final CaseService cases;
    private final CaseTimeline timeline;
    private final LtaService lta;
    private final EventBus bus;
    private final AbsenceSupport support;
    private final AuditService audit;

    public AbsenceQueueService(AbsenceQueueItemRepository queue, AbsenceContactRepository contacts, E06EpisodeRepository episodes, EpisodeViews episodeViews,
                               OhCaseRepository caseRepo, CaseService cases, CaseTimeline timeline, LtaService lta, EventBus bus,
                               AbsenceSupport support, AuditService audit) {
        this.queue = queue;
        this.contacts = contacts;
        this.episodes = episodes;
        this.episodeViews = episodeViews;
        this.caseRepo = caseRepo;
        this.cases = cases;
        this.timeline = timeline;
        this.lta = lta;
        this.bus = bus;
        this.support = support;
        this.audit = audit;
    }

    public record QueueItemView(UUID id, String employeeId, String displayName, String unit, String ruleCode, String ruleName, String action,
                                Instant raisedAt, Instant slaDueAt, String status, UUID caseId, String caseRef, Instant firstContactAt,
                                String assignedTo, String detail, boolean slaBreached, List<EpisodeViews.EpisodeView> history) {
    }

    @Transactional(readOnly = true)
    public List<QueueItemView> list(CurrentUser user, String status, String action) {
        List<QueueItem> items = queue.findAllByOrderByRaisedAtDesc().stream()
                .filter(q -> status == null || status.isBlank() ? !QueueStatus.CLOSED.name().equals(q.getStatus()) : status.equals(q.getStatus()))
                .filter(q -> action == null || action.isBlank() || action.equals(q.getAction())).toList();
        List<QueueItemView> out = new ArrayList<>();
        for (QueueItem q : items) {
            Employee e = support.employee(q.getEmployeeId()).orElse(null);
            if (e != null && support.canSeeClinical(user, e)) {
                out.add(view(q, e));
            }
        }
        audit.record("ABSENCE_QUEUE_READ", "ABSENCE_QUEUE_ITEM", null, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    @Transactional
    public QueueItemView accept(CurrentUser user, UUID id) {
        QueueItem q = load(user, id, "ABSENCE_QUEUE_ACCEPT");
        if (!QueueStatus.NEW.name().equals(q.getStatus())) {
            throw ApiException.conflict("invalid_state", "Only new queue items can be accepted");
        }
        Employee e = support.employee(q.getEmployeeId()).orElseThrow();
        OhCase c = q.getCaseId() != null ? caseRepo.findById(q.getCaseId()).orElseThrow()
                : caseRepo.findByEmployeeIdOrderByCreatedAtDesc(e.getEmployeeId()).stream()
                        .filter(x -> x.getCaseType() == CaseType.ABSENCE && x.isOpen()).findFirst()
                        .orElseGet(() -> cases.openIdempotent(OpenCaseCommand.builder(e.getEmployeeId(), CaseCategory.OTHER, CaseSource.ABSENCE_TRIGGER)
                                .caseType(CaseType.ABSENCE).sourceRef("absence-queue:" + q.getId()).earlyIntervention(true)
                                .urgency(Urgency.ROUTINE).build()));
        q.setCaseId(c.getId());
        q.setCaseRef(c.getCaseRef());
        q.setStatus(QueueStatus.ACCEPTED.name());
        q.setAssignedTo(user.username());
        q.setAcceptedAt(support.now());
        if (c.getOwnerUsername() == null) {
            c.route(c.getQueueCode(), user.username(), support.now());
        }
        if (q.getEpisodeId() != null) {
            cases.link(user, c.getId(), CaseLink.Type.ABSENCE, q.getEpisodeId().toString());
            episodes.findById(q.getEpisodeId()).filter(ep -> ep.getCaseId() == null).ifPresent(ep -> ep.setCaseId(c.getId()));
        }
        timeline.record(c.getId(), "ABSENCE_TRIGGER_ACCEPTED", "Absence trigger " + q.getRuleCode() + " accepted by Occupational Health");
        if (support.flag("absence.notify-hr-on-engagement", true)) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("employeeName", e.getDisplayName());
            params.put("unit", support.unit(e));
            params.put("caseRef", c.getCaseRef());
            params.put("contact", support.staffName(user.username()) + ", Occupational Health");
            support.notifyManagerOf(e.getEmployeeId(), "absence.oh-engaged", params, "#/absence");
            support.notifyHr("absence.oh-engaged", params, "#/absence");
            bus.publish(new E06Events.HrAbsenceStatus(UUID.randomUUID().toString(), e.getEmployeeId(), Boolean.TRUE, null, support.today()),
                    E06Events.HR_DESTINATION);
        }
        audit.record("ABSENCE_QUEUE_ACCEPT", "ABSENCE_QUEUE_ITEM", id.toString(), AuditService.Outcome.SUCCESS, q.getAction());
        return view(q, e);
    }

    @Transactional
    public QueueItemView contact(CurrentUser user, UUID id, String channel, String outcome, String note) {
        QueueItem q = load(user, id, "ABSENCE_QUEUE_CONTACT");
        if (QueueStatus.NEW.name().equals(q.getStatus()) || QueueStatus.CLOSED.name().equals(q.getStatus())) {
            throw ApiException.conflict("invalid_state", "Accept the queue item before logging contacts");
        }
        Contact c = Contact.create();
        c.setQueueItemId(q.getId());
        c.setCaseId(q.getCaseId());
        c.setEmployeeId(q.getEmployeeId());
        c.setContactedAt(support.now());
        c.setChannel(channel);
        c.setOutcome(outcome);
        c.setNote(FitnessSupport.clinical("note", note, 2000));
        c.setContactedBy(user.username());
        contacts.save(c);
        if ("REACHED".equals(outcome)) {
            if (q.getFirstContactAt() == null) {
                q.setFirstContactAt(c.getContactedAt());
            }
            q.setStatus(QueueStatus.CONTACTED.name());
            lta.contactReached(q.getEmployeeId(), user.username());
        }
        if (q.getCaseId() != null) {
            timeline.record(q.getCaseId(), "ABSENCE_CONTACT", "Contact by " + channel + ": " + outcome);
        }
        audit.record("ABSENCE_QUEUE_CONTACT", "ABSENCE_QUEUE_ITEM", id.toString(), AuditService.Outcome.SUCCESS, outcome);
        return view(q, support.employee(q.getEmployeeId()).orElse(null));
    }

    @Transactional
    public QueueItemView close(CurrentUser user, UUID id, String reason) {
        QueueItem q = load(user, id, "ABSENCE_QUEUE_CLOSE");
        if (QueueStatus.CLOSED.name().equals(q.getStatus())) {
            throw ApiException.conflict("invalid_state", "Queue item already closed");
        }
        String r = FitnessSupport.clinical("reason", reason, 500);
        if (r == null) {
            throw ApiException.badRequest("reason_required", "Give a reason for closing the item");
        }
        q.setStatus(QueueStatus.CLOSED.name());
        q.setClosedAt(support.now());
        q.setClosedBy(user.username());
        q.setCloseReason(r);
        if (q.getCaseId() != null) {
            timeline.record(q.getCaseId(), "ABSENCE_TRIGGER_CLOSED", "Absence queue item " + q.getRuleCode() + " closed");
        }
        audit.record("ABSENCE_QUEUE_CLOSE", "ABSENCE_QUEUE_ITEM", id.toString(), AuditService.Outcome.SUCCESS, null);
        return view(q, support.employee(q.getEmployeeId()).orElse(null));
    }

    private QueueItem load(CurrentUser user, UUID id, String auditAction) {
        QueueItem q = queue.findById(id).orElseThrow(() -> ApiException.notFound("Queue item"));
        support.requireClinicalUpdate(user, q.getEmployeeId(), auditAction);
        return q;
    }

    private QueueItemView view(QueueItem q, Employee e) {
        LocalDate since = support.today().minusDays(365);
        List<EpisodeViews.EpisodeView> history = episodeViews.views(episodes.findByEmployeeIdOrderByStartDateDesc(q.getEmployeeId()).stream()
                .filter(x -> x.getEndDate() == null || !x.getEndDate().isBefore(since)).toList(), true);
        boolean breached = q.getFirstContactAt() == null ? support.now().isAfter(q.getSlaDueAt()) : q.getFirstContactAt().isAfter(q.getSlaDueAt());
        return new QueueItemView(q.getId(), q.getEmployeeId(), e == null ? q.getEmployeeId() : e.getDisplayName(), support.unit(e), q.getRuleCode(),
                q.getRuleName(), q.getAction(), q.getRaisedAt(), q.getSlaDueAt(), q.getStatus(), q.getCaseId(), q.getCaseRef(), q.getFirstContactAt(),
                q.getAssignedTo(), q.getDetail(), breached, history);
    }
}
