package com.wellnessz.e07.due;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
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

import com.wellnessz.casekernel.support.AppointmentBooking;
import com.wellnessz.casekernel.support.AppointmentStatusListener;
import com.wellnessz.casekernel.support.EmployeeRecordAccess;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.common.model.Roles;
import com.wellnessz.e07.E07Events;
import com.wellnessz.e07.model.Completion;
import com.wellnessz.e07.model.CompletionRepository;
import com.wellnessz.e07.model.Enrolment;
import com.wellnessz.e07.model.EnrolmentRepository;
import com.wellnessz.e07.model.Invitation;
import com.wellnessz.e07.model.InvitationRepository;
import com.wellnessz.e07.model.Programme;
import com.wellnessz.e07.programme.Eligibility;
import com.wellnessz.e07.programme.EnrolmentEngine;
import com.wellnessz.e07.programme.ProgrammeCatalog;
import com.wellnessz.e07.support.Booker;
import com.wellnessz.e07.support.SurveillanceSupport;
import com.wellnessz.integration.outbox.EventBus;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.disclosure.DisclosureLogService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Due list, invitations (with booking through the scheduling SPI), completion / exemption, no-show re-invitation and
 * manager escalation, the employee's own view and the status-only team compliance view (E07-F02).
 */
@Service
public class DueService {

    public static final String SOURCE_TYPE = "SURVEILLANCE_INVITATION";
    static final int MAX_BULK = 5000;
    static final int MAX_ROWS = 5000;
    static final Duration RESPONSE_WINDOW = Duration.ofDays(14);

    private final EnrolmentRepository enrolments;
    private final InvitationRepository invitations;
    private final CompletionRepository completions;
    private final ProgrammeCatalog catalog;
    private final EnrolmentEngine engine;
    private final Booker booker;
    private final EmployeeRecordAccess records;
    private final SurveillanceSupport support;
    private final DisclosureLogService disclosures;
    private final EventBus bus;
    private final AuditService audit;

    public DueService(EnrolmentRepository enrolments, InvitationRepository invitations, CompletionRepository completions,
                      ProgrammeCatalog catalog, EnrolmentEngine engine, Booker booker, EmployeeRecordAccess records,
                      SurveillanceSupport support, DisclosureLogService disclosures, EventBus bus, AuditService audit) {
        this.enrolments = enrolments;
        this.invitations = invitations;
        this.completions = completions;
        this.catalog = catalog;
        this.engine = engine;
        this.booker = booker;
        this.records = records;
        this.support = support;
        this.disclosures = disclosures;
        this.bus = bus;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ views

    public record AppointmentRef(UUID id, Instant startsAt, String siteCode) {
    }

    public record EnrolmentView(UUID id, String employeeId, String displayName, String subsidiaryCode, String departmentCode,
                                String programmeCode, String programmeTitle, String reason, LocalDate lastCompletedOn, LocalDate nextDueOn,
                                String status, int invitations, Instant lastInvitedAt, AppointmentRef appointment, Instant escalatedAt) {
    }

    public record InvitationView(UUID id, Instant sentAt, String channel, String status, UUID appointmentId) {
    }

    public record InviteResult(int invited, int booked, int notBooked, List<UUID> invitationIds) {
    }

    public record MyProgramme(String programmeTitle, LocalDate nextDueOn, String status, AppointmentRef appointment) {
    }

    public record TeamRow(String employeeId, String displayName, String programmeTitle, String status, LocalDate nextDueOn, boolean escalated) {
    }

    /** Latest invitation per enrolment. */
    Map<UUID, Invitation> latestInvitations(Collection<UUID> enrolmentIds) {
        Map<UUID, Invitation> out = new HashMap<>();
        if (enrolmentIds.isEmpty()) {
            return out;
        }
        for (Invitation i : invitations.findByEnrolmentIdInOrderBySentAtDesc(enrolmentIds)) {
            out.putIfAbsent(i.getEnrolmentId(), i);
        }
        return out;
    }

    static boolean scheduled(Invitation latest) {
        return latest != null && "BOOKED".equals(latest.getStatus()) && latest.getAppointmentId() != null;
    }

    static AppointmentRef appointment(Invitation latest) {
        return scheduled(latest) ? new AppointmentRef(latest.getAppointmentId(), latest.getAppointmentStartsAt(), latest.getSiteCode()) : null;
    }

    public String status(Enrolment e, Invitation latest, LocalDate today) {
        return Eligibility.status(e.getNextDueOn(), today, e.isExempt(), e.getExemptUntil(), scheduled(latest));
    }

    private EnrolmentView view(Enrolment e, Employee emp, Programme p, Invitation latest, LocalDate today) {
        return new EnrolmentView(e.getId(), e.getEmployeeId(), emp == null ? e.getEmployeeId() : emp.getDisplayName(),
                emp == null ? null : emp.getSubsidiaryCode(), emp == null ? null : emp.getDepartmentCode(), e.getProgrammeCode(),
                p == null ? e.getProgrammeCode() : p.getTitle(), e.getReason(), e.getLastCompletedOn(), e.getNextDueOn(),
                status(e, latest, today), e.getInvitations(), e.getLastInvitedAt(), appointment(latest), e.getEscalatedAt());
    }

    // ------------------------------------------------------------------ due list

    @Transactional(readOnly = true)
    public List<EnrolmentView> due(CurrentUser user, String programme, String subsidiary, String department, String status,
                                   LocalDate dueBefore, String employeeId) {
        LocalDate today = support.today();
        List<Enrolment> active = (employeeId == null ? enrolments.findByActiveTrue() : enrolments.findByEmployeeIdAndActiveTrue(employeeId)).stream()
                .filter(e -> programme == null || programme.equals(e.getProgrammeCode()))
                .filter(e -> dueBefore == null || e.getNextDueOn().isBefore(dueBefore))
                .toList();
        Map<String, Employee> emps = support.employeesById(active.stream().map(Enrolment::getEmployeeId).distinct().toList());
        Map<String, Programme> programmes = new HashMap<>();
        Map<UUID, Invitation> latest = latestInvitations(active.stream().map(Enrolment::getId).toList());
        List<EnrolmentView> out = new ArrayList<>();
        for (Enrolment e : active) {
            Employee emp = emps.get(e.getEmployeeId());
            if (emp == null || subsidiary != null && !subsidiary.equals(emp.getSubsidiaryCode())
                    || department != null && !department.equals(emp.getDepartmentCode())) {
                continue;
            }
            Programme p = programmes.computeIfAbsent(e.getProgrammeCode(), c -> catalog.current(c).orElse(null));
            EnrolmentView v = view(e, emp, p, latest.get(e.getId()), today);
            if (status != null && !status.equals(v.status())) {
                continue;
            }
            out.add(v);
            if (out.size() >= MAX_ROWS) {
                break;
            }
        }
        out.sort(Comparator.comparing(EnrolmentView::nextDueOn).thenComparing(EnrolmentView::displayName));
        audit.record("SURVEILLANCE_DUE_LIST", "ENROLMENT", null, AuditService.Outcome.SUCCESS,
                "rows=" + out.size() + (programme == null ? "" : " programme=" + programme) + (employeeId == null ? "" : " employee=" + employeeId));
        return out;
    }

    @Transactional
    public EnrolmentEngine.RecalcResult recalculate(CurrentUser user) {
        EnrolmentEngine.RecalcResult r = engine.recalculate(null);
        audit.record("SURVEILLANCE_RECALCULATE", "ENROLMENT", null, AuditService.Outcome.SUCCESS,
                "enrolled=" + r.enrolled() + " updated=" + r.updated() + " removed=" + r.removed());
        return r;
    }

    // ------------------------------------------------------------------ invitations

    @Transactional
    public InviteResult invite(CurrentUser user, List<UUID> enrolmentIds, boolean book, String siteCode, UUID campaignId) {
        if (enrolmentIds == null || enrolmentIds.isEmpty() || enrolmentIds.size() > MAX_BULK) {
            throw ApiException.badRequest("invalid_bulk", "Between 1 and " + MAX_BULK + " enrolments per call");
        }
        if (siteCode != null && !support.siteExists(siteCode)) {
            throw ApiException.badRequest("unknown_site", "Unknown or inactive clinic site");
        }
        Set<UUID> ids = new LinkedHashSet<>(enrolmentIds);
        List<Enrolment> list = enrolments.findAllById(ids).stream().filter(Enrolment::isActive).toList();
        if (list.size() != ids.size()) {
            throw ApiException.notFound("Enrolment");
        }
        int booked = 0;
        List<UUID> created = new ArrayList<>();
        Map<String, Programme> programmes = new HashMap<>();
        for (Enrolment e : list) {
            Programme p = programmes.computeIfAbsent(e.getProgrammeCode(), c -> catalog.current(c).orElse(null));
            Invitation i = sendInvitation(e, p, user.username(), book, siteCode, campaignId);
            created.add(i.getId());
            if ("BOOKED".equals(i.getStatus())) {
                booked++;
            }
        }
        audit.record("SURVEILLANCE_INVITE", "ENROLMENT", null, AuditService.Outcome.SUCCESS,
                "invited=" + created.size() + " booked=" + booked + " book=" + book);
        return new InviteResult(created.size(), booked, created.size() - booked, created);
    }

    /** Sends one invitation (content-free template) and, when asked and available, books the first free slot. */
    Invitation sendInvitation(Enrolment e, Programme p, String actor, boolean book, String siteCode, UUID campaignId) {
        Instant now = support.now();
        Invitation i = new Invitation();
        i.setId(UUID.randomUUID());
        i.setEnrolmentId(e.getId());
        i.setEmployeeId(e.getEmployeeId());
        i.setSequenceNo(e.getInvitations() + 1);
        i.setSentAt(now);
        i.setChannel("EMAIL");
        i.setStatus("INVITED");
        i.setCampaignId(campaignId);
        i.setSentBy(actor);
        i.setUpdatedAt(now);
        if (book && p != null) {
            LocalDate earliest = support.today().plusDays(1);
            Optional<AppointmentBooking.Booking> b = booker.book(actor, new AppointmentBooking.BookingRequest(e.getEmployeeId(),
                    p.getServiceCode(), siteCode, earliest, campaignId, SOURCE_TYPE, i.getId().toString(), null));
            if (b.isPresent()) {
                i.setStatus("BOOKED");
                i.setAppointmentId(b.get().appointmentId());
                i.setAppointmentStartsAt(b.get().startsAt());
                i.setSiteCode(b.get().siteCode());
            }
        }
        invitations.save(i);
        e.setInvitations(e.getInvitations() + 1);
        e.setLastInvitedAt(now);
        e.setUpdatedAt(now);
        support.notifyEmployee(e.getEmployeeId(), "surveillance.invitation", Map.of("dueDate", e.getNextDueOn().toString()));
        return i;
    }

    @Transactional(readOnly = true)
    public List<InvitationView> invitations(CurrentUser user, UUID enrolmentId) {
        Enrolment e = enrolments.findById(enrolmentId).orElseThrow(() -> ApiException.notFound("Enrolment"));
        List<InvitationView> out = invitations.findByEnrolmentIdOrderBySentAtDesc(e.getId()).stream()
                .map(i -> new InvitationView(i.getId(), i.getSentAt(), i.getChannel(), i.getStatus(), i.getAppointmentId())).toList();
        audit.record("SURVEILLANCE_INVITATION_LOG", "ENROLMENT", enrolmentId.toString(), AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    // ------------------------------------------------------------------ appointment callbacks (SPI) & ignored invitations

    /** Status change of an appointment booked for a surveillance invitation (called inside the scheduling transaction). */
    @Transactional
    public void appointmentChanged(UUID appointmentId, String sourceRef, AppointmentStatusListener.Status status) {
        UUID invitationId;
        if (sourceRef == null) {
            return;
        }
        try {
            invitationId = UUID.fromString(sourceRef);
        } catch (IllegalArgumentException ex) {
            return;
        }
        Invitation i = invitations.findById(invitationId).orElse(null);
        if (i == null) {
            return;
        }
        Enrolment e = enrolments.findById(i.getEnrolmentId()).orElse(null);
        Instant now = support.now();
        i.setUpdatedAt(now);
        switch (status) {
            case BOOKED, CHECKED_IN -> {
                if (!"ATTENDED".equals(i.getStatus())) {
                    i.setStatus("BOOKED");
                    i.setAppointmentId(appointmentId);
                }
            }
            case ATTENDED -> i.setStatus("ATTENDED");
            case CANCELLED -> i.setStatus("CANCELLED");
            case NO_SHOW -> {
                i.setStatus("NO_SHOW");
                if (e != null && e.isActive()) {
                    ignored(e, "system");
                }
            }
            default -> {
            }
        }
        audit.recordAs("system", "MODULE", "SURVEILLANCE_APPOINTMENT_" + status.name(), "INVITATION", i.getId().toString(),
                AuditService.Outcome.SUCCESS, null);
    }

    /** Applies the no-show policy: re-invite (and re-book) or escalate to the line manager once. */
    NoShowPolicy.Action ignored(Enrolment e, String actor) {
        int max = support.configInt("surveillance.max-invitations", 2);
        NoShowPolicy.Action a = NoShowPolicy.onIgnored(e.getInvitations(), max, e.getEscalatedAt() != null);
        switch (a) {
            case REINVITE -> {
                Programme p = catalog.current(e.getProgrammeCode()).orElse(null);
                sendInvitation(e, p, actor, booker.available(), null, null);
            }
            case ESCALATE -> {
                e.setEscalatedAt(support.now());
                e.setUpdatedAt(support.now());
                Employee emp = support.employee(e.getEmployeeId()).orElse(null);
                if (emp != null) {
                    support.notifyManagerOf(e.getEmployeeId(), "surveillance.overdue-manager", Map.of("employeeName", emp.getDisplayName(),
                            "unit", emp.getDepartmentCode(), "dueDate", e.getNextDueOn().toString()));
                }
                invitations.findByEnrolmentIdOrderBySentAtDesc(e.getId()).stream().findFirst().ifPresent(i -> {
                    if ("NO_SHOW".equals(i.getStatus()) || "INVITED".equals(i.getStatus())) {
                        i.setStatus("ESCALATED");
                        i.setUpdatedAt(support.now());
                    }
                });
                audit.recordAs(actor, "MODULE", "SURVEILLANCE_ESCALATED", "ENROLMENT", e.getId().toString(), AuditService.Outcome.SUCCESS,
                        "invitations=" + e.getInvitations());
            }
            default -> {
            }
        }
        return a;
    }

    // ------------------------------------------------------------------ completion & exemption

    public record CompleteCommand(LocalDate performedOn, String outcome, UUID encounterId, List<UUID> resultIds) {
    }

    @Transactional
    public EnrolmentView complete(CurrentUser user, UUID id, CompleteCommand cmd) {
        Enrolment e = enrolments.findById(id).filter(Enrolment::isActive).orElseThrow(() -> ApiException.notFound("Enrolment"));
        Employee emp = records.requireClinical(user, e.getEmployeeId(), DataClassification.CLINICAL, AccessPolicy.Action.UPDATE,
                "SURVEILLANCE_COMPLETE");
        LocalDate today = support.today();
        if (cmd.performedOn() == null || cmd.performedOn().isAfter(today)) {
            throw ApiException.badRequest("invalid_performed_on", "The examination date is required and cannot be in the future");
        }
        if (e.getLastCompletedOn() != null && cmd.performedOn().isBefore(e.getLastCompletedOn())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "earlier_than_last",
                    "A later examination is already recorded for this programme");
        }
        Programme p = catalog.current(e.getProgrammeCode()).orElseThrow(() -> ApiException.notFound("Programme"));
        recordCompletion(e, p, cmd.performedOn(), cmd.outcome(), cmd.encounterId(), cmd.resultIds(), user.username());
        audit.record("SURVEILLANCE_COMPLETE", "ENROLMENT", id.toString(), AuditService.Outcome.SUCCESS, cmd.outcome());
        return view(e, emp, p, latestInvitations(List.of(e.getId())).get(e.getId()), today);
    }

    /** Records a completed examination (also used by crew examinations) and recomputes the next due date. */
    public void recordCompletion(Enrolment e, Programme p, LocalDate performedOn, String outcome, UUID encounterId, List<UUID> resultIds,
                                 String actor) {
        Instant now = support.now();
        Completion c = new Completion();
        c.setId(UUID.randomUUID());
        c.setEnrolmentId(e.getId());
        c.setEmployeeId(e.getEmployeeId());
        c.setProgrammeCode(e.getProgrammeCode());
        c.setPerformedOn(performedOn);
        c.setOutcome(outcome);
        c.setEncounterId(encounterId);
        c.setResultIds(resultIds == null || resultIds.isEmpty() ? null
                : SurveillanceSupport.joinCsv(resultIds.stream().limit(50).map(UUID::toString).toList()));
        c.setRecordedBy(actor);
        c.setRecordedAt(now);
        completions.save(c);
        if (e.getLastCompletedOn() == null || !performedOn.isBefore(e.getLastCompletedOn())) {
            e.setLastCompletedOn(performedOn);
            e.setLastOutcome(outcome);
        }
        engine.refreshDue(e, p, engine.dateOfBirth(e.getEmployeeId()), engine.graceDays());
        e.setInvitations(0);
        e.setEscalatedAt(null);
        e.setDueEventFor(null);
        e.setUpdatedAt(now);
        invitations.findByEnrolmentIdOrderBySentAtDesc(e.getId()).stream()
                .filter(i -> "INVITED".equals(i.getStatus()) || "BOOKED".equals(i.getStatus()))
                .forEach(i -> {
                    i.setStatus("ATTENDED");
                    i.setUpdatedAt(now);
                });
    }

    @Transactional
    public EnrolmentView exempt(CurrentUser user, UUID id, String reason, LocalDate until) {
        Enrolment e = enrolments.findById(id).filter(Enrolment::isActive).orElseThrow(() -> ApiException.notFound("Enrolment"));
        Employee emp = records.requireClinical(user, e.getEmployeeId(), DataClassification.CLINICAL, AccessPolicy.Action.UPDATE,
                "SURVEILLANCE_EXEMPT");
        if (until != null && until.isBefore(support.today())) {
            throw ApiException.badRequest("invalid_until", "The exemption end date cannot be in the past");
        }
        e.setExempt(true);
        e.setExemptReason(SurveillanceSupport.clinical("reason", reason, 1000));
        e.setExemptUntil(until);
        e.setUpdatedAt(support.now());
        audit.record("SURVEILLANCE_EXEMPT", "ENROLMENT", id.toString(), AuditService.Outcome.SUCCESS, until == null ? "open-ended" : until.toString());
        Programme p = catalog.current(e.getProgrammeCode()).orElse(null);
        return view(e, emp, p, latestInvitations(List.of(e.getId())).get(e.getId()), support.today());
    }

    // ------------------------------------------------------------------ employee & team views

    @Transactional(readOnly = true)
    public List<MyProgramme> mine(String employeeId) {
        LocalDate today = support.today();
        List<Enrolment> list = enrolments.findByEmployeeIdAndActiveTrue(employeeId);
        Map<UUID, Invitation> latest = latestInvitations(list.stream().map(Enrolment::getId).toList());
        return list.stream().map(e -> {
            Programme p = catalog.current(e.getProgrammeCode()).orElse(null);
            Invitation i = latest.get(e.getId());
            return new MyProgramme(p == null ? e.getProgrammeCode() : p.getTitle(), e.getNextDueOn(), status(e, i, today), appointment(i));
        }).sorted(Comparator.comparing(MyProgramme::nextDueOn)).toList();
    }

    @Transactional
    public List<TeamRow> team(CurrentUser user) {
        if (!user.hasAnyRole(Roles.MANAGER, Roles.HR)) {
            throw ApiException.forbidden("Team compliance is available to managers and HR");
        }
        LocalDate today = support.today();
        List<Enrolment> active = enrolments.findByActiveTrue();
        Map<String, Employee> emps = support.employeesById(active.stream().map(Enrolment::getEmployeeId).distinct().toList());
        Map<String, Boolean> inTeam = new HashMap<>();
        Map<String, Programme> programmes = new HashMap<>();
        List<TeamRow> out = new ArrayList<>();
        Set<String> disclosed = new LinkedHashSet<>();
        for (Enrolment e : active) {
            Employee emp = emps.get(e.getEmployeeId());
            if (emp == null || !inTeam.computeIfAbsent(emp.getEmployeeId(), k -> support.inTeam(user, emp))
                    || user.employeeId() != null && user.employeeId().equals(emp.getEmployeeId())) {
                continue;
            }
            Programme p = programmes.computeIfAbsent(e.getProgrammeCode(), c -> catalog.current(c).orElse(null));
            String status = e.isExempt() && (e.getExemptUntil() == null || !e.getExemptUntil().isBefore(today)) ? "CURRENT"
                    : Eligibility.dateStatus(e.getNextDueOn(), today);
            out.add(new TeamRow(emp.getEmployeeId(), emp.getDisplayName(), p == null ? e.getProgrammeCode() : p.getTitle(), status,
                    e.getNextDueOn(), e.getEscalatedAt() != null));
            disclosed.add(emp.getEmployeeId());
            if (out.size() >= MAX_ROWS) {
                break;
            }
        }
        String recipientType = user.hasRole(Roles.MANAGER) ? "MANAGER" : "HR";
        for (String id : disclosed) {
            disclosures.record(id, recipientType, user.displayName() == null ? user.username() : user.displayName(), "SURVEILLANCE_STATUS",
                    id, "Surveillance compliance status", "FITNESS_FOR_WORK", null, user.username());
        }
        out.sort(Comparator.comparing(TeamRow::displayName).thenComparing(TeamRow::programmeTitle));
        audit.record("SURVEILLANCE_TEAM_COMPLIANCE", "ENROLMENT", null, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    // ------------------------------------------------------------------ nightly processing

    public record DueRun(int dueEvents, int reinvited, int escalated) {
    }

    /** Publishes {@code surveillance.enrolment.due.v1} once per due period and follows up invitations nobody responded to. */
    @Transactional
    public DueRun processDue(LocalDate today) {
        int events = 0;
        for (Enrolment e : enrolments.findByActiveTrue()) {
            if (e.isExempt() && (e.getExemptUntil() == null || !e.getExemptUntil().isBefore(today))) {
                continue;
            }
            if (!"CURRENT".equals(Eligibility.dateStatus(e.getNextDueOn(), today)) && !e.getNextDueOn().equals(e.getDueEventFor())) {
                e.setDueEventFor(e.getNextDueOn());
                bus.publish(new E07Events.EnrolmentDue(e.getId().toString(), e.getEmployeeId(), e.getProgrammeCode(), e.getNextDueOn()));
                events++;
            }
        }
        int reinvited = 0;
        int escalated = 0;
        Instant cutoff = support.now().minus(RESPONSE_WINDOW);
        Set<UUID> handled = new LinkedHashSet<>();
        for (Invitation i : invitations.findByStatusAndSentAtBefore("INVITED", cutoff)) {
            Enrolment e = enrolments.findById(i.getEnrolmentId()).orElse(null);
            if (e == null || !e.isActive() || !handled.add(e.getId())) {
                continue;
            }
            Invitation latest = invitations.findByEnrolmentIdOrderBySentAtDesc(e.getId()).stream().findFirst().orElse(null);
            if (latest == null || !latest.getId().equals(i.getId()) || "CURRENT".equals(Eligibility.dateStatus(e.getNextDueOn(), today))) {
                continue;
            }
            NoShowPolicy.Action a = ignored(e, "system");
            if (a == NoShowPolicy.Action.REINVITE) {
                reinvited++;
            } else if (a == NoShowPolicy.Action.ESCALATE) {
                escalated++;
            }
        }
        return new DueRun(events, reinvited, escalated);
    }
}
