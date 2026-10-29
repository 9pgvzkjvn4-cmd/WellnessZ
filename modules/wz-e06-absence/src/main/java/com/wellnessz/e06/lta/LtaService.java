package com.wellnessz.e06.lta;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.wellnessz.casekernel.CaseService;
import com.wellnessz.casekernel.model.CaseCategory;
import com.wellnessz.casekernel.model.CaseLink;
import com.wellnessz.casekernel.model.CaseLinkRepository;
import com.wellnessz.casekernel.model.CaseSource;
import com.wellnessz.casekernel.model.CaseType;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OhCaseRepository;
import com.wellnessz.casekernel.model.OpenCaseCommand;
import com.wellnessz.casekernel.model.Urgency;
import com.wellnessz.casekernel.support.AbsenceSignalListener;
import com.wellnessz.casekernel.support.AppointmentBooking;
import com.wellnessz.casekernel.support.CaseAccess;
import com.wellnessz.casekernel.support.CaseTimeline;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.e05.model.RtwPlan;
import com.wellnessz.e05.model.RtwPlanRepository;
import com.wellnessz.e05.support.FitnessSupport;
import com.wellnessz.e06.E06Events;
import com.wellnessz.e06.model.AbsenceEnums.AdviceStatus;
import com.wellnessz.e06.model.AbsenceEnums.HrStage;
import com.wellnessz.e06.model.AbsenceEnums.MilestoneStatus;
import com.wellnessz.e06.model.AbsenceEnums.MilestoneType;
import com.wellnessz.e06.model.AdviceReport;
import com.wellnessz.e06.model.AdviceReportRepository;
import com.wellnessz.e06.model.AdviceRequest;
import com.wellnessz.e06.model.AdviceRequestRepository;
import com.wellnessz.e06.model.Contact;
import com.wellnessz.e06.model.AbsenceContactRepository;
import com.wellnessz.e06.model.Episode;
import com.wellnessz.e06.model.LtaConference;
import com.wellnessz.e06.model.LtaConferenceRepository;
import com.wellnessz.e06.model.LtaMilestone;
import com.wellnessz.e06.model.LtaMilestoneRepository;
import com.wellnessz.e06.model.LtaPathway;
import com.wellnessz.e06.model.LtaPathwayRepository;
import com.wellnessz.e06.model.AbsenceQueueItemRepository;
import com.wellnessz.e06.rules.AbsenceFacts;
import com.wellnessz.e06.support.AbsenceSupport;
import com.wellnessz.integration.outbox.EventBus;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Long-term absence pathway (E06-F04): kernel ABSENCE case, milestone plan, fortnightly contact schedule, case conferences,
 * HR stage (status only), E05 RTW plan link, overdue milestone alerts and the {@link AbsenceSignalListener} signal.
 */
@Service("absenceLtaService")
public class LtaService {

    private static final Logger log = LoggerFactory.getLogger(LtaService.class);
    public static final String ACTIVE = "ACTIVE";
    public static final String CLOSED = "CLOSED";
    public static final String CONTACT_SOURCE = "ABSENCE_CONTACT";
    static final Set<String> OPEN_MILESTONE = Set.of(MilestoneStatus.PENDING.name(), MilestoneStatus.OVERDUE.name());
    static final Set<String> PARTICIPANTS = Set.of("EMPLOYEE", "MANAGER", "HR", "OH");

    private final LtaPathwayRepository pathways;
    private final LtaMilestoneRepository milestones;
    private final AbsenceContactRepository contacts;
    private final LtaConferenceRepository conferences;
    private final AdviceRequestRepository adviceRequests;
    private final AdviceReportRepository adviceReports;
    private final AbsenceQueueItemRepository queue;
    private final RtwPlanRepository rtwPlans;
    private final CaseService cases;
    private final OhCaseRepository caseRepo;
    private final CaseLinkRepository links;
    private final CaseAccess access;
    private final CaseTimeline timeline;
    private final List<AbsenceSignalListener> signals;
    private final ObjectProvider<AppointmentBooking> booking;
    private final TransactionTemplate newTx;
    private final EventBus bus;
    private final AbsenceSupport support;
    private final AuditService audit;

    public LtaService(LtaPathwayRepository pathways, LtaMilestoneRepository milestones, AbsenceContactRepository contacts,
                      LtaConferenceRepository conferences, AdviceRequestRepository adviceRequests, AdviceReportRepository adviceReports,
                      AbsenceQueueItemRepository queue, RtwPlanRepository rtwPlans, CaseService cases, OhCaseRepository caseRepo, CaseLinkRepository links,
                      CaseAccess access, CaseTimeline timeline, List<AbsenceSignalListener> signals, ObjectProvider<AppointmentBooking> booking,
                      PlatformTransactionManager transactions, EventBus bus, AbsenceSupport support, AuditService audit) {
        this.pathways = pathways;
        this.milestones = milestones;
        this.contacts = contacts;
        this.conferences = conferences;
        this.adviceRequests = adviceRequests;
        this.adviceReports = adviceReports;
        this.queue = queue;
        this.rtwPlans = rtwPlans;
        this.cases = cases;
        this.caseRepo = caseRepo;
        this.links = links;
        this.access = access;
        this.timeline = timeline;
        this.signals = signals;
        this.booking = booking;
        this.newTx = new TransactionTemplate(transactions);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.bus = bus;
        this.support = support;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ views

    public record NextMilestone(UUID id, int week, String type, LocalDate dueDate, String status) {
    }

    public record Pathway(UUID caseId, String caseRef, String employeeId, String displayName, String unit, LocalDate absenceStart,
                          long weeksAbsent, String status, String hrStage, NextMilestone nextMilestone, long overdueCount, UUID rtwPlanId) {
    }

    public record MilestoneView(UUID id, int week, String type, LocalDate dueDate, String status, Instant completedAt, String completedBy,
                                String note, UUID appointmentId) {
    }

    public record ContactView(UUID id, Instant at, String channel, String outcome, String note, String by, LocalDate nextContactDue) {
    }

    public record AdviceRef(UUID id, String ref, String status, Instant releasedAt) {
    }

    public record RtwPlanRef(UUID id, String status) {
    }

    public record ConferenceView(UUID id, Instant scheduledFor, List<String> participants, String note, String by) {
    }

    public record PathwayDetail(UUID caseId, String caseRef, String employeeId, String displayName, String unit, LocalDate absenceStart,
                                long weeksAbsent, String status, String hrStage, String hrStageNote, NextMilestone nextMilestone, long overdueCount,
                                UUID rtwPlanId, LocalDate rtwDate, List<MilestoneView> milestones, List<ContactView> contacts,
                                List<AdviceRef> adviceReports, RtwPlanRef rtwPlan, List<ConferenceView> conferences) {
    }

    public record WorkspaceNext(int week, String type, LocalDate dueDate) {
    }

    public record WorkspacePathway(UUID caseId, String caseRef, String employeeId, String displayName, LocalDate absenceStart, long weeksAbsent,
                                   String hrStage, WorkspaceNext nextMilestone, int adviceReleased) {
    }

    // ------------------------------------------------------------------ start (system, from the trigger engine)

    public Optional<LtaPathway> active(String employeeId) {
        return pathways.findFirstByEmployeeIdAndStatus(employeeId, ACTIVE);
    }

    /**
     * Starts the pathway for a long-term episode: reuses the employee's open ABSENCE case or opens one, plans the milestones,
     * signals {@link AbsenceSignalListener}s once and books the first contact appointment when scheduling is available.
     */
    @Transactional
    public OhCase start(Employee e, Episode ep, LocalDate asOf) {
        Optional<LtaPathway> existing = active(e.getEmployeeId());
        if (existing.isPresent()) {
            return caseRepo.findById(existing.get().getCaseId()).orElseThrow();
        }
        OhCase c = caseRepo.findByEmployeeIdOrderByCreatedAtDesc(e.getEmployeeId()).stream()
                .filter(x -> x.getCaseType() == CaseType.ABSENCE && x.isOpen()).findFirst()
                .orElseGet(() -> cases.openIdempotent(OpenCaseCommand.builder(e.getEmployeeId(), CaseCategory.OTHER, CaseSource.ABSENCE_TRIGGER)
                        .caseType(CaseType.ABSENCE).sourceRef("absence-lta:" + ep.getId()).earlyIntervention(true).urgency(Urgency.ROUTINE)
                        .occurredOn(ep.getStartDate()).build()));
        LtaPathway p = new LtaPathway();
        p.setCaseId(c.getId());
        p.setCaseRef(c.getCaseRef());
        p.setEmployeeId(e.getEmployeeId());
        p.setEpisodeId(ep.getId());
        p.setAbsenceStart(ep.getStartDate());
        p.setStatus(ACTIVE);
        p.setHrStage(HrStage.NONE.name());
        p.setStartedAt(support.now());
        pathways.save(p);
        List<LtaMilestone> planned = new ArrayList<>();
        for (MilestonePlan.Planned m : MilestonePlan.plan(ep.getStartDate())) {
            planned.add(milestones.save(milestone(c.getId(), m)));
        }
        // a contact already made by OH since the absence started satisfies the week-2 contact milestone
        Instant since = ep.getStartDate().atStartOfDay().toInstant(ZoneOffset.UTC);
        contacts.findByEmployeeIdAndContactedAtGreaterThanEqualOrderByContactedAtAsc(e.getEmployeeId(), since).stream()
                .filter(x -> "REACHED".equals(x.getOutcome())).findFirst()
                .ifPresent(x -> planned.stream().filter(m -> MilestoneType.CONTACT.name().equals(m.getMilestoneType())).findFirst()
                        .ifPresent(m -> done(m, x.getContactedBy(), "Contact made " + x.getContactedAt().toString().substring(0, 10))));
        if (ep.getCaseId() == null) {
            ep.setCaseId(c.getId());
        }
        links.save(new CaseLink(c.getId(), CaseLink.Type.ABSENCE, ep.getId().toString(), "absence-feed", support.now()));
        long days = AbsenceFacts.fact(ep).days(asOf);
        timeline.record(c.getId(), "LTA_STARTED", "Long-term absence pathway started (" + days + " days absent)");
        bus.publish(new E06Events.LtaStarted(c.getId().toString(), e.getEmployeeId()));
        if (!ep.isLtaSignalled()) {
            ep.setLtaSignalled(true);
            for (AbsenceSignalListener l : signals) {
                try {
                    l.longTermAbsence(e.getEmployeeId(), c.getId(), ep.getStartDate(), days);
                } catch (RuntimeException ex) {
                    log.warn("Absence signal listener {} failed: {}", l.getClass().getSimpleName(), ex.getClass().getSimpleName());
                }
            }
        }
        planned.stream().filter(m -> MilestoneType.CONTACT.name().equals(m.getMilestoneType()) && OPEN_MILESTONE.contains(m.getStatus()))
                .findFirst().ifPresent(m -> bookContactAfterCommit(e.getEmployeeId(), c.getId(), m.getId(), m.getDueDate().isBefore(asOf) ? asOf : m.getDueDate()));
        audit.record("LTA_STARTED", "CASE", c.getId().toString(), AuditService.Outcome.SUCCESS, null);
        return c;
    }

    private LtaMilestone milestone(UUID caseId, MilestonePlan.Planned m) {
        LtaMilestone x = LtaMilestone.create();
        x.setCaseId(caseId);
        x.setWeek(m.week());
        x.setMilestoneType(m.type().name());
        x.setDueDate(m.dueDate());
        x.setStatus(MilestoneStatus.PENDING.name());
        return x;
    }

    /** Books an ABSENCE_REVIEW appointment for a contact milestone after the pathway is committed (scheduling is optional). */
    private void bookContactAfterCommit(String employeeId, UUID caseId, UUID milestoneId, LocalDate earliest) {
        AppointmentBooking b = booking.getIfAvailable();
        if (b == null) {
            return;
        }
        Runnable task = () -> {
            try {
                newTx.executeWithoutResult(s -> b.bookNext("absence-lta", new AppointmentBooking.BookingRequest(employeeId, "ABSENCE_REVIEW", null,
                        earliest, null, CONTACT_SOURCE, milestoneId.toString(), caseId)).ifPresent(bk ->
                        milestones.findById(milestoneId).ifPresent(m -> m.setAppointmentId(bk.appointmentId()))));
            } catch (RuntimeException ex) {
                log.info("Absence contact appointment not booked: {}", ex.getClass().getSimpleName());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    /** The absence episode closed with a return date: RTW review milestone + E05 RTW plan link. */
    @Transactional
    public void episodeClosed(Episode ep) {
        if (ep.getEndDate() == null) {
            return;
        }
        Optional<LtaPathway> po = active(ep.getEmployeeId()).filter(p -> ep.getId().equals(p.getEpisodeId())
                || (!ep.getStartDate().isAfter(p.getAbsenceStart().plusDays(1)) && !ep.getEndDate().isBefore(p.getAbsenceStart())));
        if (po.isEmpty()) {
            return;
        }
        LtaPathway p = po.get();
        LocalDate rtw = ep.getEndDate().plusDays(1);
        p.setRtwDate(rtw);
        List<LtaMilestone> ms = milestones.findByCaseIdOrderByDueDateAscWeekAsc(p.getCaseId());
        if (ms.stream().noneMatch(m -> MilestoneType.RTW_REVIEW.name().equals(m.getMilestoneType()))) {
            milestones.save(milestone(p.getCaseId(), MilestonePlan.rtwReview(p.getAbsenceStart(), rtw)));
        }
        latestRtwPlan(p.getCaseId()).ifPresent(plan -> p.setRtwPlanId(plan.getId()));
        timeline.record(p.getCaseId(), "LTA_RTW", "Absence ended – return to work on " + rtw + "; RTW review scheduled");
    }

    private Optional<RtwPlan> latestRtwPlan(UUID caseId) {
        List<RtwPlan> plans = rtwPlans.findByCaseIdOrderByCreatedAtAsc(caseId).stream().filter(x -> !"CANCELLED".equals(x.getStatus())).toList();
        return plans.isEmpty() ? Optional.empty() : Optional.of(plans.get(plans.size() - 1));
    }

    // ------------------------------------------------------------------ OH

    @Transactional(readOnly = true)
    public List<Pathway> list(CurrentUser user, String status) {
        List<LtaPathway> list = status == null || status.isBlank() ? pathways.findAllByOrderByAbsenceStartDesc()
                : pathways.findByStatusOrderByAbsenceStartAsc(status);
        List<Pathway> out = new ArrayList<>();
        for (LtaPathway p : list) {
            Employee e = support.employee(p.getEmployeeId()).orElse(null);
            if (e != null && support.canSeeClinical(user, e)) {
                out.add(pathway(p, e, milestones.findByCaseIdOrderByDueDateAscWeekAsc(p.getCaseId())));
            }
        }
        audit.record("LTA_LIST_READ", "CASE", null, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    @Transactional(readOnly = true)
    public PathwayDetail detail(CurrentUser user, UUID caseId) {
        access.clinical(user, caseId, AccessPolicy.Action.READ, "LTA_READ");
        LtaPathway p = pathways.findById(caseId).orElseThrow(() -> ApiException.notFound("Pathway"));
        Employee e = support.employee(p.getEmployeeId()).orElse(null);
        List<LtaMilestone> ms = milestones.findByCaseIdOrderByDueDateAscWeekAsc(caseId);
        Pathway head = pathway(p, e, ms);
        List<ContactView> cs = contacts.findByCaseIdOrderByContactedAtDesc(caseId).stream().map(c -> new ContactView(c.getId(),
                c.getContactedAt(), c.getChannel(), c.getOutcome(), c.getNote(), c.getContactedBy(), c.getNextContactDue())).toList();
        List<AdviceRef> advice = adviceRefs(p);
        RtwPlanRef rtw = (p.getRtwPlanId() != null ? rtwPlans.findById(p.getRtwPlanId()) : latestRtwPlan(caseId))
                .map(x -> new RtwPlanRef(x.getId(), x.getStatus())).orElse(null);
        List<ConferenceView> conf = conferences.findByCaseIdOrderByScheduledForAsc(caseId).stream().map(c -> new ConferenceView(c.getId(),
                c.getScheduledFor(), List.of(c.getParticipants().split(",")), c.getNote(), c.getCreatedBy())).toList();
        audit.record("LTA_READ", "CASE", caseId.toString(), AuditService.Outcome.SUCCESS, null);
        return new PathwayDetail(head.caseId(), head.caseRef(), head.employeeId(), head.displayName(), head.unit(), head.absenceStart(),
                head.weeksAbsent(), head.status(), head.hrStage(), p.getHrStageNote(), head.nextMilestone(), head.overdueCount(),
                rtw == null ? null : rtw.id(), p.getRtwDate(), ms.stream().map(LtaService::milestoneView).toList(), cs, advice, rtw, conf);
    }

    private List<AdviceRef> adviceRefs(LtaPathway p) {
        List<AdviceRef> out = new ArrayList<>();
        for (AdviceRequest r : adviceRequests.findByEmployeeIdOrderByRequestedAtDesc(p.getEmployeeId())) {
            boolean related = p.getCaseId().equals(r.getCaseId())
                    || !r.getRequestedAt().isBefore(p.getAbsenceStart().atStartOfDay().toInstant(ZoneOffset.UTC));
            if (!related) {
                continue;
            }
            Instant released = adviceReports.findByRequestIdOrderByVersionDesc(r.getId()).stream().map(AdviceReport::getReleasedAt)
                    .filter(java.util.Objects::nonNull).findFirst().orElse(null);
            out.add(new AdviceRef(r.getId(), r.getRef(), r.getStatus(), released));
        }
        return out;
    }

    @Transactional
    public PathwayDetail completeMilestone(CurrentUser user, UUID caseId, UUID milestoneId, String note) {
        LtaMilestone m = loadMilestone(user, caseId, milestoneId, "LTA_MILESTONE_COMPLETE");
        done(m, user.username(), FitnessSupport.clinical("note", note, 2000));
        timeline.record(caseId, "LTA_MILESTONE", "Milestone week " + m.getWeek() + " " + m.getMilestoneType() + " completed");
        if (MilestoneType.RTW_REVIEW.name().equals(m.getMilestoneType())) {
            close(caseId, user.username());
        }
        audit.record("LTA_MILESTONE_COMPLETE", "CASE", caseId.toString(), AuditService.Outcome.SUCCESS, m.getMilestoneType());
        return detail(user, caseId);
    }

    @Transactional
    public PathwayDetail skipMilestone(CurrentUser user, UUID caseId, UUID milestoneId, String reason) {
        LtaMilestone m = loadMilestone(user, caseId, milestoneId, "LTA_MILESTONE_SKIP");
        String r = FitnessSupport.clinical("reason", reason, 1000);
        if (r == null) {
            throw ApiException.badRequest("reason_required", "Give a reason for skipping the milestone");
        }
        m.setStatus(MilestoneStatus.SKIPPED.name());
        m.setCompletedAt(support.now());
        m.setCompletedBy(user.username());
        m.setNote(r);
        timeline.record(caseId, "LTA_MILESTONE", "Milestone week " + m.getWeek() + " " + m.getMilestoneType() + " skipped");
        audit.record("LTA_MILESTONE_SKIP", "CASE", caseId.toString(), AuditService.Outcome.SUCCESS, m.getMilestoneType());
        return detail(user, caseId);
    }

    private LtaMilestone loadMilestone(CurrentUser user, UUID caseId, UUID milestoneId, String auditAction) {
        access.clinical(user, caseId, AccessPolicy.Action.UPDATE, auditAction);
        LtaPathway p = pathways.findById(caseId).orElseThrow(() -> ApiException.notFound("Pathway"));
        if (!ACTIVE.equals(p.getStatus())) {
            throw ApiException.conflict("pathway_closed", "The long-term absence pathway is closed");
        }
        LtaMilestone m = milestones.findById(milestoneId).filter(x -> x.getCaseId().equals(caseId))
                .orElseThrow(() -> ApiException.notFound("Milestone"));
        if (!OPEN_MILESTONE.contains(m.getStatus())) {
            throw ApiException.conflict("milestone_closed", "The milestone is already completed or skipped");
        }
        return m;
    }

    private void done(LtaMilestone m, String by, String note) {
        m.setStatus(MilestoneStatus.DONE.name());
        m.setCompletedAt(support.now());
        m.setCompletedBy(by);
        if (note != null) {
            m.setNote(note);
        }
    }

    private void close(UUID caseId, String by) {
        LtaPathway p = pathways.findById(caseId).orElseThrow();
        p.setStatus(CLOSED);
        p.setClosedAt(support.now());
        for (LtaMilestone m : milestones.findByCaseIdOrderByDueDateAscWeekAsc(caseId)) {
            if (OPEN_MILESTONE.contains(m.getStatus())) {
                m.setStatus(MilestoneStatus.SKIPPED.name());
                m.setCompletedAt(support.now());
                m.setCompletedBy(by);
                m.setNote("Pathway closed after the return-to-work review");
            }
        }
        queue.findFirstByEmployeeIdAndActionAndStatusNot(p.getEmployeeId(), "LONG_TERM", "CLOSED").ifPresent(q -> {
            q.setStatus("CLOSED");
            q.setClosedAt(support.now());
            q.setClosedBy(by);
            q.setCloseReason("Returned to work – pathway closed");
        });
        timeline.record(caseId, "LTA_CLOSED", "Long-term absence pathway closed after the return-to-work review");
    }

    @Transactional
    public PathwayDetail addContact(CurrentUser user, UUID caseId, String channel, String outcome, String note, LocalDate nextContactDue) {
        access.clinical(user, caseId, AccessPolicy.Action.UPDATE, "LTA_CONTACT");
        LtaPathway p = pathways.findById(caseId).orElseThrow(() -> ApiException.notFound("Pathway"));
        LocalDate today = support.today();
        if (nextContactDue != null && nextContactDue.isBefore(today)) {
            throw ApiException.badRequest("invalid_next_contact", "nextContactDue must not be in the past");
        }
        Contact c = Contact.create();
        c.setCaseId(caseId);
        c.setEmployeeId(p.getEmployeeId());
        c.setContactedAt(support.now());
        c.setChannel(channel);
        c.setOutcome(outcome);
        c.setNote(FitnessSupport.clinical("note", note, 2000));
        c.setContactedBy(user.username());
        c.setNextContactDue(nextContactDue != null ? nextContactDue : today.plusDays(support.intConfig("absence.lta.contact-interval-days", 14)));
        contacts.save(c);
        if ("REACHED".equals(outcome)) {
            contactReached(p.getEmployeeId(), user.username());
        }
        timeline.record(caseId, "LTA_CONTACT", "Contact by " + channel + ": " + outcome + "; next contact due " + c.getNextContactDue());
        audit.record("LTA_CONTACT", "CASE", caseId.toString(), AuditService.Outcome.SUCCESS, outcome);
        return detail(user, caseId);
    }

    /** A REACHED contact completes the earliest open CONTACT milestone and sets the first-contact KPI of an open LONG_TERM item. */
    @Transactional
    public void contactReached(String employeeId, String by) {
        active(employeeId).ifPresent(p -> milestones.findByCaseIdOrderByDueDateAscWeekAsc(p.getCaseId()).stream()
                .filter(m -> MilestoneType.CONTACT.name().equals(m.getMilestoneType()) && OPEN_MILESTONE.contains(m.getStatus()))
                .findFirst().ifPresent(m -> done(m, by, null)));
        queue.findFirstByEmployeeIdAndActionAndStatusNot(employeeId, "LONG_TERM", "CLOSED").filter(q -> q.getFirstContactAt() == null)
                .ifPresent(q -> {
                    q.setFirstContactAt(support.now());
                    if (!"NEW".equals(q.getStatus())) {
                        q.setStatus("CONTACTED");
                    }
                });
    }

    @Transactional
    public PathwayDetail conference(CurrentUser user, UUID caseId, String scheduledFor, List<String> participants, String note) {
        access.clinical(user, caseId, AccessPolicy.Action.UPDATE, "LTA_CONFERENCE");
        LtaPathway p = pathways.findById(caseId).orElseThrow(() -> ApiException.notFound("Pathway"));
        if (participants == null || participants.isEmpty() || !PARTICIPANTS.containsAll(participants)) {
            throw ApiException.badRequest("invalid_participants", "Participants: EMPLOYEE, MANAGER, HR, OH");
        }
        Instant when = parseWhen(scheduledFor);
        LtaConference c = LtaConference.create();
        c.setCaseId(caseId);
        c.setScheduledFor(when);
        c.setParticipants(String.join(",", participants.stream().distinct().toList()));
        c.setNote(FitnessSupport.clinical("note", note, 2000));
        c.setCreatedBy(user.username());
        c.setCreatedAt(support.now());
        conferences.save(c);
        milestones.findByCaseIdOrderByDueDateAscWeekAsc(p.getCaseId()).stream()
                .filter(m -> MilestoneType.CASE_CONFERENCE.name().equals(m.getMilestoneType()) && OPEN_MILESTONE.contains(m.getStatus()))
                .findFirst().ifPresent(m -> done(m, user.username(), "Case conference scheduled for " + when.toString().substring(0, 10)));
        timeline.record(caseId, "LTA_CONFERENCE", "Case conference scheduled for " + when.toString().substring(0, 16).replace('T', ' ')
                + " (" + c.getParticipants() + ")");
        audit.record("LTA_CONFERENCE", "CASE", caseId.toString(), AuditService.Outcome.SUCCESS, null);
        return detail(user, caseId);
    }

    static Instant parseWhen(String s) {
        try {
            if (s.length() == 10) {
                return LocalDate.parse(s).atTime(9, 0).toInstant(ZoneOffset.UTC);
            }
            try {
                return java.time.OffsetDateTime.parse(s).toInstant();
            } catch (java.time.format.DateTimeParseException noOffset) {
                return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC);
            }
        } catch (RuntimeException e) {
            throw ApiException.badRequest("invalid_date", "scheduledFor must be an ISO date or date-time");
        }
    }

    // ------------------------------------------------------------------ HR / manager

    @Transactional
    public WorkspacePathway hrStage(CurrentUser user, UUID caseId, String stage, String note) {
        if (!user.hasRole(Roles.HR)) {
            throw ApiException.forbidden("HR only");
        }
        LtaPathway p = pathways.findById(caseId).orElseThrow(() -> ApiException.notFound("Pathway"));
        Employee e = support.requireInScope(user, p.getEmployeeId(), "LTA_HR_STAGE");
        HrStage s = HrStage.valueOf(stage);
        p.setHrStage(s.name());
        p.setHrStageNote(FitnessSupport.functional("note", note, 1000));
        p.setHrStageBy(user.username());
        p.setHrStageAt(support.now());
        if (s != HrStage.NONE && ACTIVE.equals(p.getStatus())) {
            milestones.findByCaseIdOrderByDueDateAscWeekAsc(caseId).stream()
                    .filter(m -> MilestoneType.HR_REVIEW.name().equals(m.getMilestoneType()) && OPEN_MILESTONE.contains(m.getStatus()))
                    .findFirst().ifPresent(m -> done(m, user.username(), "HR stage " + s.name()));
        }
        timeline.record(caseId, "LTA_HR_STAGE", "HR stage set to " + s.name());
        audit.record("LTA_HR_STAGE", "CASE", caseId.toString(), AuditService.Outcome.SUCCESS, s.name());
        return workspaceView(p, e);
    }

    @Transactional(readOnly = true)
    public List<WorkspacePathway> workspace(CurrentUser user) {
        if (!user.hasAnyRole(Roles.MANAGER, Roles.HR)) {
            throw ApiException.forbidden("Managers and HR only");
        }
        List<WorkspacePathway> out = new ArrayList<>();
        for (LtaPathway p : pathways.findByStatusOrderByAbsenceStartAsc(ACTIVE)) {
            Employee e = support.employee(p.getEmployeeId()).orElse(null);
            if (e != null && support.inScope(user, e)) {
                out.add(workspaceView(p, e));
                audit.record("LTA_WORKSPACE_READ", "EMPLOYEE", e.getEmployeeId(), AuditService.Outcome.SUCCESS, "status-only");
            }
        }
        return out;
    }

    private WorkspacePathway workspaceView(LtaPathway p, Employee e) {
        NextMilestone n = next(milestones.findByCaseIdOrderByDueDateAscWeekAsc(p.getCaseId()));
        int released = (int) adviceRefs(p).stream().filter(a -> AdviceStatus.RELEASED.name().equals(a.status())).count();
        return new WorkspacePathway(p.getCaseId(), p.getCaseRef(), p.getEmployeeId(), e == null ? p.getEmployeeId() : e.getDisplayName(),
                p.getAbsenceStart(), weeks(p), p.getHrStage(), n == null ? null : new WorkspaceNext(n.week(), n.type(), n.dueDate()), released);
    }

    // ------------------------------------------------------------------ nightly

    public record OverdueRun(int markedOverdue, int notified) {
    }

    /** Nightly {@code absence-lta}: open milestones past their due date become OVERDUE; the case owner is alerted once. */
    @Transactional
    public OverdueRun processOverdue(LocalDate today) {
        int marked = 0;
        int notified = 0;
        for (LtaMilestone m : milestones.findByStatusAndDueDateLessThanOrderByDueDateAsc(MilestoneStatus.PENDING.name(), today)) {
            LtaPathway p = pathways.findById(m.getCaseId()).orElse(null);
            if (p == null || !ACTIVE.equals(p.getStatus())) {
                continue;
            }
            m.setStatus(MilestoneStatus.OVERDUE.name());
            marked++;
            if (m.getOverdueNotifiedAt() == null) {
                Map<String, String> params = new LinkedHashMap<>();
                params.put("caseRef", p.getCaseRef());
                params.put("dueDate", m.getDueDate().toString());
                String owner = caseRepo.findById(p.getCaseId()).map(OhCase::getOwnerUsername).orElse(null);
                if (owner != null) {
                    support.notifyStaff(owner, "absence.milestone-overdue", params, "#/absence/lta/" + p.getCaseId());
                } else {
                    support.notifyRole(Roles.CASE_MANAGER, "absence.milestone-overdue", params, "#/absence/lta/" + p.getCaseId());
                }
                m.setOverdueNotifiedAt(support.now());
                notified++;
            }
        }
        return new OverdueRun(marked, notified);
    }

    // ------------------------------------------------------------------ appointments (E14 callbacks)

    @Transactional
    public void appointmentStatus(UUID appointmentId, String milestoneRef, String status) {
        Optional<LtaMilestone> mo;
        try {
            mo = milestones.findById(UUID.fromString(milestoneRef));
        } catch (IllegalArgumentException e) {
            mo = milestones.findFirstByAppointmentId(appointmentId);
        }
        mo.ifPresent(m -> {
            LtaPathway p = pathways.findById(m.getCaseId()).orElse(null);
            if (p == null) {
                return;
            }
            switch (status) {
                case "BOOKED" -> m.setAppointmentId(appointmentId);
                case "CANCELLED" -> m.setAppointmentId(null);
                case "ATTENDED", "NO_SHOW" -> {
                    boolean attended = "ATTENDED".equals(status);
                    Contact c = Contact.create();
                    c.setCaseId(p.getCaseId());
                    c.setEmployeeId(p.getEmployeeId());
                    c.setContactedAt(support.now());
                    c.setChannel("IN_PERSON");
                    c.setOutcome(attended ? "REACHED" : "NO_ANSWER");
                    c.setContactedBy("scheduling");
                    c.setNextContactDue(support.today().plusDays(support.intConfig("absence.lta.contact-interval-days", 14)));
                    contacts.save(c);
                    if (attended && OPEN_MILESTONE.contains(m.getStatus())) {
                        done(m, "scheduling", "Absence review appointment attended");
                        contactReached(p.getEmployeeId(), "scheduling");
                    }
                    timeline.record(p.getCaseId(), "LTA_CONTACT", "Absence review appointment " + (attended ? "attended" : "missed"));
                }
                default -> {
                    // CHECKED_IN: nothing to record
                }
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    private Pathway pathway(LtaPathway p, Employee e, List<LtaMilestone> ms) {
        long overdue = ms.stream().filter(m -> MilestoneStatus.OVERDUE.name().equals(m.getStatus())).count();
        UUID rtwPlanId = p.getRtwPlanId() != null ? p.getRtwPlanId() : latestRtwPlan(p.getCaseId()).map(RtwPlan::getId).orElse(null);
        return new Pathway(p.getCaseId(), p.getCaseRef(), p.getEmployeeId(), e == null ? p.getEmployeeId() : e.getDisplayName(), support.unit(e),
                p.getAbsenceStart(), weeks(p), p.getStatus(), p.getHrStage(), next(ms), overdue, rtwPlanId);
    }

    private long weeks(LtaPathway p) {
        LocalDate until = p.getRtwDate() != null ? p.getRtwDate() : support.today();
        return MilestonePlan.weeksAbsent(p.getAbsenceStart(), until);
    }

    static NextMilestone next(List<LtaMilestone> ms) {
        return ms.stream().filter(m -> OPEN_MILESTONE.contains(m.getStatus()))
                .min(Comparator.comparing(LtaMilestone::getDueDate).thenComparing(LtaMilestone::getWeek))
                .map(m -> new NextMilestone(m.getId(), m.getWeek(), m.getMilestoneType(), m.getDueDate(), m.getStatus())).orElse(null);
    }

    static MilestoneView milestoneView(LtaMilestone m) {
        return new MilestoneView(m.getId(), m.getWeek(), m.getMilestoneType(), m.getDueDate(), m.getStatus(), m.getCompletedAt(), m.getCompletedBy(),
                m.getNote(), m.getAppointmentId());
    }
}
