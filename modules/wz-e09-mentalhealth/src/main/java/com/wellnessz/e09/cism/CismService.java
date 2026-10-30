package com.wellnessz.e09.cism;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.e09.casework.SupportCaseService;
import com.wellnessz.e09.casework.SupportCaseService.OpenSupport;
import com.wellnessz.e09.model.CismAffected;
import com.wellnessz.e09.model.CismEvent;
import com.wellnessz.e09.model.E09CismAffectedRepository;
import com.wellnessz.e09.model.E09CismEventRepository;
import com.wellnessz.e09.model.MentalHealthEnums.CismCategory;
import com.wellnessz.e09.model.MentalHealthEnums.Concern;
import com.wellnessz.e09.model.MentalHealthEnums.Risk;
import com.wellnessz.e09.model.MentalHealthEnums.Source;
import com.wellnessz.e09.model.SupportCase;
import com.wellnessz.e09.rules.CismRules;
import com.wellnessz.e09.support.MhSupport;
import com.wellnessz.masterdata.DutyAssignment;
import com.wellnessz.masterdata.DutyAssignmentRepository;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Critical incident stress management (E09-F04 / E09-BL-010 / -011): events from the incident feed (or created manually), the
 * affected-person list from crew / shift / facility duty assignments within ± the duty window, offers within the 72-hour SLA,
 * responses, sessions and 1- / 4-week follow-ups, with optional referral into the confidential support pathway. The safety
 * manager sees counts only.
 */
@Service
public class CismService {

    public static final String RESOURCE = "CISM_EVENT";

    private final E09CismEventRepository events;
    private final E09CismAffectedRepository affected;
    private final DutyAssignmentRepository duties;
    private final SupportCaseService cases;
    private final MhSupport support;
    private final AuditService audit;

    public CismService(E09CismEventRepository events, E09CismAffectedRepository affected, DutyAssignmentRepository duties, SupportCaseService cases,
                       MhSupport support, AuditService audit) {
        this.events = events;
        this.affected = affected;
        this.duties = duties;
        this.cases = cases;
        this.support = support;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ creation

    public List<String> triggerClasses() {
        return support.listConfig("cism.trigger-classes", "FATALITY,ASSAULT,FUME_EVENT,AIRCRAFT_ACCIDENT,SERIOUS_INJURY,EMERGENCY_LANDING");
    }

    private int windowHours() {
        return support.intConfig("cism.duty-window-hours", 2);
    }

    /** From the incident feed – idempotent on the incident id. */
    @Transactional
    public CismEvent fromIncident(IncidentFacts f) {
        if (f.incidentId() == null || events.findByIncidentId(f.incidentId()).isPresent()) {
            return null;
        }
        Instant occurredAt = f.occurredAt() == null ? support.now() : f.occurredAt();
        CismRules.Window w = CismRules.window(occurredAt, windowHours());
        List<CismRules.Duty> own = f.employeeId() == null || f.employeeId().isBlank() ? List.of()
                : duties.findForEmployee(f.employeeId(), w.from(), w.to()).stream().map(CismService::duty).toList();
        Set<String> refs = CismRules.dutyRefs(own);
        Set<String> groups = CismRules.proximityGroups(own);
        List<CismRules.Duty> co = new ArrayList<>();
        if (!refs.isEmpty()) {
            duties.findOnDuties(refs, w.from(), w.to()).forEach(d -> co.add(duty(d)));
        }
        if (!groups.isEmpty()) {
            duties.findInProximityGroups(groups, w.from(), w.to()).forEach(d -> co.add(duty(d)));
        }
        String cls = f.incidentClass() == null ? null : f.incidentClass().trim().toUpperCase(java.util.Locale.ROOT);
        String category = cls != null && isCategory(cls) ? cls : CismCategory.SERIOUS_INCIDENT.name();
        String title = "Critical incident – " + category.replace('_', ' ').toLowerCase(java.util.Locale.ROOT)
                + (refs.isEmpty() ? "" : " (" + String.join(", ", refs) + ")");
        String location = f.location() != null ? f.location() : f.unitCode();
        CismEvent e = save(title, category, occurredAt, location, refs, "INCIDENT", f.incidentId(), "system");
        addAffected(e, CismRules.affected(f.employeeId(), own, co, w));
        support.notifyClinicians("support.urgent-callback", Map.of("reference", e.getRef(), "level", "critical incident"), "#/cism/" + e.getId());
        audit.recordAs("system", "INTEGRATION", "CISM_EVENT_CREATED", RESOURCE, e.getId().toString(), AuditService.Outcome.SUCCESS, category);
        return e;
    }

    private static boolean isCategory(String s) {
        for (CismCategory c : CismCategory.values()) {
            if (c.name().equals(s)) {
                return true;
            }
        }
        return false;
    }

    public record ManualCommand(String title, CismCategory category, Instant occurredAt, String location, List<String> dutyRefs) {
    }

    @Transactional
    public EventDetail create(CurrentUser user, ManualCommand cmd) {
        if (cmd.occurredAt().isAfter(support.now())) {
            throw MhSupport.unprocessable("invalid_time", "The event lies in the future");
        }
        String title = MhSupport.screened("title", cmd.title(), 200);
        String location = MhSupport.screened("location", cmd.location(), 200);
        CismRules.Window w = CismRules.window(cmd.occurredAt(), windowHours());
        Set<String> refs = cmd.dutyRefs() == null ? Set.of() : cmd.dutyRefs().stream().filter(Objects::nonNull).map(String::strip)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<CismRules.Duty> co = refs.isEmpty() ? List.of() : duties.findOnDuties(refs, w.from(), w.to()).stream().map(CismService::duty).toList();
        CismEvent e = save(title, cmd.category().name(), cmd.occurredAt(), location, refs, "MANUAL", null, user.username());
        addAffected(e, CismRules.affected(null, List.of(), co, w));
        audit.record("CISM_EVENT_CREATED", RESOURCE, e.getId().toString(), AuditService.Outcome.SUCCESS, cmd.category().name());
        return detail(e);
    }

    private CismEvent save(String title, String category, Instant occurredAt, String location, Set<String> refs, String source, String incidentId,
                           String actor) {
        CismEvent e = CismEvent.create();
        e.setRef(support.nextRef("CISM"));
        e.setTitle(title);
        e.setCategory(category);
        e.setOccurredAt(occurredAt);
        e.setLocation(location);
        String joined = String.join(",", refs);
        e.setDutyRefs(joined.isEmpty() ? null : joined.length() > 500 ? joined.substring(0, 500) : joined);
        e.setSource(source);
        e.setIncidentId(incidentId);
        e.setOfferDueBy(CismRules.offerDueBy(occurredAt, support.intConfig("cism.offer-sla-hours", 72)));
        e.setCreatedBy(actor);
        e.setCreatedAt(support.now());
        return events.save(e);
    }

    private void addAffected(CismEvent e, Map<String, String> people) {
        CismRules.FollowUps fu = CismRules.followUps(e.getOccurredAt().atZone(ZoneOffset.UTC).toLocalDate());
        people.forEach((employeeId, role) -> {
            CismAffected a = CismAffected.create();
            a.setEventId(e.getId());
            a.setEmployeeId(employeeId);
            a.setDutyRole(role);
            a.setFollowUp1wDue(fu.oneWeek());
            a.setFollowUp4wDue(fu.fourWeeks());
            a.setUpdatedAt(support.now());
            affected.save(a);
        });
    }

    private static CismRules.Duty duty(DutyAssignment d) {
        return new CismRules.Duty(d.getEmployeeId(), d.getDutyRef(), d.getProximityGroup(), d.getDutyRole(), d.getStartsAt(), d.getEndsAt());
    }

    // ------------------------------------------------------------------ reads

    public record EventSummary(UUID id, String ref, String title, String category, Instant occurredAt, String location, String source,
                               int affected, int offered, int accepted, Instant offerDueBy, Boolean slaMet) {
    }

    public record FollowUp(LocalDate dueOn, boolean done, Instant sentAt) {
    }

    public record AffectedView(String employeeId, String displayName, String dutyRole, Instant offerSentAt, String response, String preferredContact,
                               Instant respondedAt, String sessionType, Instant attendedAt, FollowUp followUp1w, FollowUp followUp4w, boolean referred,
                               String supportCaseRef) {
    }

    public record EventDetail(UUID id, String ref, String title, String category, Instant occurredAt, String location, String source,
                              List<String> dutyRefs, Instant offerDueBy, Boolean slaMet, int offered, int accepted, List<AffectedView> affected) {
    }

    @Transactional(readOnly = true)
    public List<EventSummary> list(CurrentUser user) {
        List<CismEvent> all = events.findAllByOrderByOccurredAtDesc();
        Map<UUID, List<CismAffected>> byEvent = affected.findByEventIdIn(all.stream().map(CismEvent::getId).toList()).stream()
                .collect(Collectors.groupingBy(CismAffected::getEventId));
        audit.record("CISM_EVENT_LIST", RESOURCE, null, AuditService.Outcome.SUCCESS, user.hasRole(Roles.SAFETY_MANAGER) ? "counts" : null);
        return all.stream().map(e -> summary(e, byEvent.getOrDefault(e.getId(), List.of()))).toList();
    }

    private EventSummary summary(CismEvent e, List<CismAffected> list) {
        int offered = (int) list.stream().filter(a -> a.getOfferSentAt() != null).count();
        int accepted = (int) list.stream().filter(a -> "ACCEPTED".equals(a.getResponse())).count();
        return new EventSummary(e.getId(), e.getRef(), e.getTitle(), e.getCategory(), e.getOccurredAt(), e.getLocation(), e.getSource(), list.size(),
                offered, accepted, e.getOfferDueBy(), CismRules.slaMet(e.getOfferDueBy(), list.stream().map(CismAffected::getOfferSentAt).toList(),
                        support.now()));
    }

    @Transactional(readOnly = true)
    public EventDetail get(CurrentUser user, UUID id) {
        CismEvent e = events.findById(id).orElseThrow(() -> ApiException.notFound("CISM event"));
        audit.record("CISM_EVENT_READ", RESOURCE, id.toString(), AuditService.Outcome.SUCCESS, null);
        return detail(e);
    }

    private EventDetail detail(CismEvent e) {
        List<CismAffected> list = affected.findByEventIdOrderByEmployeeIdAsc(e.getId());
        EventSummary s = summary(e, list);
        return new EventDetail(e.getId(), e.getRef(), e.getTitle(), e.getCategory(), e.getOccurredAt(), e.getLocation(), e.getSource(),
                e.getDutyRefs() == null ? List.of() : List.of(e.getDutyRefs().split(",")), e.getOfferDueBy(), s.slaMet(), s.offered(), s.accepted(),
                list.stream().map(this::view).toList());
    }

    private AffectedView view(CismAffected a) {
        String supportRef = a.getSupportCaseId() == null ? null : cases.load(a.getSupportCaseId()).getCaseRef();
        return new AffectedView(a.getEmployeeId(), support.name(a.getEmployeeId()), a.getDutyRole(), a.getOfferSentAt(), a.getResponse(),
                a.getPreferredContact(), a.getRespondedAt(), a.getSessionType(), a.getAttendedAt(),
                new FollowUp(a.getFollowUp1wDue(), a.isFollowUp1wDone(), a.getFollowUp1wSentAt()),
                new FollowUp(a.getFollowUp4wDue(), a.isFollowUp4wDone(), a.getFollowUp4wSentAt()), a.isReferred(), supportRef);
    }

    // ------------------------------------------------------------------ offers & tracking

    public record OfferResult(int sent, EventDetail event) {
    }

    @Transactional
    public OfferResult sendOffers(CurrentUser user, UUID id) {
        CismEvent e = events.findById(id).orElseThrow(() -> ApiException.notFound("CISM event"));
        Instant now = support.now();
        int sent = 0;
        for (CismAffected a : affected.findByEventIdOrderByEmployeeIdAsc(id)) {
            if (a.getOfferSentAt() != null) {
                continue;
            }
            a.setOfferSentAt(now);
            a.setUpdatedAt(now);
            support.notifyEmployee(a.getEmployeeId(), "cism.offer", Map.of(), "support");
            sent++;
        }
        audit.record("CISM_OFFERS_SENT", RESOURCE, id.toString(), AuditService.Outcome.SUCCESS, "sent=" + sent);
        return new OfferResult(sent, detail(e));
    }

    public record AffectedUpdate(String response, String sessionType, Instant attendedAt, Boolean followUp1wDone, Boolean followUp4wDone,
                                 Boolean referToSupport) {
    }

    @Transactional
    public AffectedView update(CurrentUser user, UUID id, String employeeId, AffectedUpdate u) {
        events.findById(id).orElseThrow(() -> ApiException.notFound("CISM event"));
        CismAffected a = affected.findByEventIdAndEmployeeId(id, employeeId).orElseThrow(() -> ApiException.notFound("Affected person"));
        Instant now = support.now();
        if (u.response() != null) {
            a.setResponse(u.response());
            a.setRespondedAt(now);
        }
        if (u.sessionType() != null) {
            a.setSessionType(u.sessionType());
        }
        if (u.attendedAt() != null) {
            if (u.attendedAt().isAfter(now)) {
                throw MhSupport.unprocessable("invalid_time", "Attendance lies in the future");
            }
            a.setAttendedAt(u.attendedAt());
        }
        if (u.followUp1wDone() != null) {
            a.setFollowUp1wDone(u.followUp1wDone());
        }
        if (u.followUp4wDone() != null) {
            a.setFollowUp4wDone(u.followUp4wDone());
        }
        if (Boolean.TRUE.equals(u.referToSupport()) && !a.isReferred()) {
            SupportCase c = cases.openCaseOf(employeeId).orElseGet(() -> cases.open(new OpenSupport(employeeId, Source.CISM, Concern.TRAUMA,
                    Risk.MODERATE, user.username(), "CISM_EVENT", id + ":" + employeeId)));
            cases.event(c, "CISM_REFERRAL", "Referred from a critical-incident event", user.username());
            a.setReferred(true);
            a.setSupportCaseId(c.getId());
        }
        a.setUpdatedAt(now);
        audit.record("CISM_AFFECTED_UPDATE", RESOURCE, id.toString(), AuditService.Outcome.SUCCESS, null);
        return view(a);
    }

    // ------------------------------------------------------------------ employee: my offers

    public record MyOffer(UUID id, String eventRef, String title, Instant occurredAt, Instant offerSentAt, String response, String preferredContact,
                          Instant respondedAt) {
    }

    @Transactional(readOnly = true)
    public List<MyOffer> myOffers(CurrentUser user) {
        if (user.employeeId() == null) {
            return List.of();
        }
        return affected.findByEmployeeIdAndOfferSentAtIsNotNullOrderByOfferSentAtDesc(user.employeeId()).stream().map(this::myOffer).toList();
    }

    private MyOffer myOffer(CismAffected a) {
        CismEvent e = events.findById(a.getEventId()).orElseThrow();
        return new MyOffer(a.getId(), e.getRef(), e.getTitle(), e.getOccurredAt(), a.getOfferSentAt(), a.getResponse(), a.getPreferredContact(),
                a.getRespondedAt());
    }

    @Transactional
    public MyOffer respond(CurrentUser user, UUID offerId, String response, String preferredContact) {
        CismAffected a = affected.findById(offerId).orElse(null);
        if (a == null || user.employeeId() == null || !user.employeeId().equals(a.getEmployeeId()) || a.getOfferSentAt() == null) {
            audit.record("CISM_OFFER_RESPONSE", RESOURCE, offerId.toString(), AuditService.Outcome.DENIED, "Not the employee's offer");
            throw ApiException.notFound("Offer");
        }
        Instant now = support.now();
        a.setResponse(response);
        a.setPreferredContact(preferredContact);
        a.setRespondedAt(now);
        a.setUpdatedAt(now);
        audit.record("CISM_OFFER_RESPONSE", RESOURCE, a.getEventId().toString(), AuditService.Outcome.SUCCESS, response);
        return myOffer(a);
    }

    // ------------------------------------------------------------------ job: 1- and 4-week follow-ups

    @Transactional
    public int sendFollowUps() {
        LocalDate today = support.today();
        Instant now = support.now();
        int sent = 0;
        for (CismAffected a : affected.findFollowUpsDue(today)) {
            boolean any = false;
            if (!a.isFollowUp1wDone() && a.getFollowUp1wSentAt() == null && !a.getFollowUp1wDue().isAfter(today)) {
                a.setFollowUp1wSentAt(now);
                any = true;
            }
            if (!a.isFollowUp4wDone() && a.getFollowUp4wSentAt() == null && !a.getFollowUp4wDue().isAfter(today)) {
                a.setFollowUp4wSentAt(now);
                any = true;
            }
            if (any) {
                a.setUpdatedAt(now);
                support.notifyEmployee(a.getEmployeeId(), "cism.follow-up", Map.of(), "support");
                sent++;
            }
        }
        return sent;
    }
}
