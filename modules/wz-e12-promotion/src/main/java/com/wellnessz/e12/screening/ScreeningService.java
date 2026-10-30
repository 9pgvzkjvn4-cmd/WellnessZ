package com.wellnessz.e12.screening;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.CaseService;
import com.wellnessz.casekernel.model.CaseCategory;
import com.wellnessz.casekernel.model.CaseSource;
import com.wellnessz.casekernel.model.CaseTask;
import com.wellnessz.casekernel.model.CaseType;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OpenCaseCommand;
import com.wellnessz.casekernel.support.AppointmentBooking;
import com.wellnessz.casekernel.support.CaseTimeline;
import com.wellnessz.casekernel.support.CaseWorkService;
import com.wellnessz.casekernel.support.EmployeeRecordAccess;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.common.model.Roles;
import com.wellnessz.documents.DocumentService;
import com.wellnessz.documents.model.DocumentShare;
import com.wellnessz.e12.E12Events;
import com.wellnessz.e12.campaign.PromotionCampaignService;
import com.wellnessz.e12.model.Campaign;
import com.wellnessz.e12.model.E12CampaignRepository;
import com.wellnessz.e12.model.E12ScreeningEventRepository;
import com.wellnessz.e12.model.E12ScreeningParticipantRepository;
import com.wellnessz.e12.model.E12ScreeningResultRepository;
import com.wellnessz.e12.model.ScreeningEvent;
import com.wellnessz.e12.model.ScreeningParticipant;
import com.wellnessz.e12.model.ScreeningResult;
import com.wellnessz.e12.rules.ScreeningThresholds;
import com.wellnessz.e12.support.PromotionSupport;
import com.wellnessz.integration.outbox.EventBus;
import com.wellnessz.masterdata.ClinicSite;
import com.wellnessz.masterdata.ClinicSiteRepository;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

import tools.jackson.core.type.TypeReference;

/**
 * Screening events & follow-up (E12-F02): events (biometric, MSK, hearing, vision, mental wellbeing), participants booked via
 * the {@link AppointmentBooking} SPI, result capture against configurable thresholds and – for abnormal results – a
 * follow-up kernel case with a due task inside 48 hours, a signed {@code RESULT_LETTER} shared with the employee and the
 * content-free {@code screening.follow-up} notification. Results are clinical: only the OH team sees them.
 */
@Service
public class ScreeningService {

    public static final String TYPES = "BIOMETRIC|MSK|HEARING|VISION|MENTAL_WELLBEING";
    public static final String SOURCE_TYPE = "SCREENING_EVENT";

    private final E12ScreeningEventRepository events;
    private final E12ScreeningParticipantRepository participants;
    private final E12ScreeningResultRepository results;
    private final E12CampaignRepository campaigns;
    private final PromotionCampaignService campaignService;
    private final ClinicSiteRepository sites;
    private final ObjectProvider<AppointmentBooking> booking;
    private final CaseService cases;
    private final CaseWorkService work;
    private final CaseTimeline timeline;
    private final EmployeeRecordAccess records;
    private final DocumentService documents;
    private final PromotionSupport support;
    private final EventBus bus;
    private final AuditService audit;

    public ScreeningService(E12ScreeningEventRepository events, E12ScreeningParticipantRepository participants, E12ScreeningResultRepository results,
                            E12CampaignRepository campaigns, PromotionCampaignService campaignService, ClinicSiteRepository sites,
                            ObjectProvider<AppointmentBooking> booking, CaseService cases, CaseWorkService work, CaseTimeline timeline,
                            EmployeeRecordAccess records, DocumentService documents, PromotionSupport support, EventBus bus, AuditService audit) {
        this.events = events;
        this.participants = participants;
        this.results = results;
        this.campaigns = campaigns;
        this.campaignService = campaignService;
        this.sites = sites;
        this.booking = booking;
        this.cases = cases;
        this.work = work;
        this.timeline = timeline;
        this.records = records;
        this.documents = documents;
        this.support = support;
        this.bus = bus;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ DTOs

    public record EventCommand(UUID campaignId, @NotBlank @Pattern(regexp = TYPES) String type,
                               @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9_\\-]{1,39}") String siteCode, @NotNull LocalDate date) {
    }

    public record ParticipantsCommand(@NotEmpty @Size(max = 500) List<@NotBlank @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String> employeeIds) {
    }

    public record Measures(@DecimalMin("50") @DecimalMax("300") BigDecimal systolic,
                           @DecimalMin("30") @DecimalMax("200") BigDecimal diastolic,
                           @DecimalMin("10") @DecimalMax("80") BigDecimal bmi,
                           @DecimalMin("1") @DecimalMax("40") BigDecimal glucoseMmol,
                           @DecimalMin("1") @DecimalMax("20") BigDecimal cholesterolMmol,
                           @DecimalMin("30") @DecimalMax("250") BigDecimal waistCm,
                           @DecimalMin("0") @DecimalMax("10") BigDecimal mskScore,
                           @DecimalMin("14") @DecimalMax("70") BigDecimal wellbeingScore) {

        public Map<String, BigDecimal> asMap() {
            Map<String, BigDecimal> m = new LinkedHashMap<>();
            put(m, "systolic", systolic);
            put(m, "diastolic", diastolic);
            put(m, "bmi", bmi);
            put(m, "glucoseMmol", glucoseMmol);
            put(m, "cholesterolMmol", cholesterolMmol);
            put(m, "waistCm", waistCm);
            put(m, "mskScore", mskScore);
            put(m, "wellbeingScore", wellbeingScore);
            return m;
        }

        private static void put(Map<String, BigDecimal> m, String k, BigDecimal v) {
            if (v != null) {
                m.put(k, v);
            }
        }
    }

    public record ResultCommand(@NotBlank @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String employeeId, @NotNull @Valid Measures measures) {
    }

    public record ResultView(UUID id, UUID eventId, String employeeId, String displayName, Map<String, BigDecimal> measures, List<String> flags,
                             String outcome, boolean abnormal, UUID followUpCaseId, String followUpCaseRef, UUID caseId, String caseRef, Instant followUpDueAt, Instant followUpOpenedAt, UUID letterDocumentId,
                             String recordedBy, Instant recordedAt) {
    }

    public record ParticipantView(UUID id, String employeeId, String displayName, String unitCode, String status, UUID appointmentId,
                                  Instant appointmentStartsAt, boolean resultRecorded, ResultView result) {
    }

    public record EventView(UUID id, String ref, UUID campaignId, String campaignTitle, String type, String siteCode, String siteName,
                            LocalDate date, String status, int participantCount, int screenedCount, Integer abnormalCount,
                            Integer followedUpCount, List<ParticipantView> participants) {
    }

    public record AddResult(int added, int alreadyRegistered, int booked, List<String> unknown, EventView event) {
    }

    // ------------------------------------------------------------------ events

    @Transactional
    public EventView create(CurrentUser user, EventCommand cmd) {
        if (sites.findById(cmd.siteCode()).filter(ClinicSite::isActive).isEmpty()) {
            throw PromotionSupport.unprocessable("unknown_site", "Unknown clinic site");
        }
        if (cmd.campaignId() != null) {
            Campaign c = campaignService.require(cmd.campaignId());
            if ("CANCELLED".equals(c.getStatus()) || "COMPLETED".equals(c.getStatus())) {
                throw ApiException.conflict("campaign_closed", "The campaign is completed or cancelled");
            }
        }
        ScreeningEvent e = new ScreeningEvent();
        e.setId(UUID.randomUUID());
        e.setEventRef(support.nextRef("SCR", campaigns.nextRef()));
        e.setCampaignId(cmd.campaignId());
        e.setEventType(cmd.type());
        e.setSiteCode(cmd.siteCode());
        e.setEventDate(cmd.date());
        e.setStatus("PLANNED");
        e.setCreatedBy(user.username());
        e.setCreatedAt(support.now());
        e.setUpdatedAt(support.now());
        events.save(e);
        audit.record("SCREENING_EVENT_CREATE", "SCREENING_EVENT", e.getId().toString(), AuditService.Outcome.SUCCESS, cmd.type());
        return view(user, e, false);
    }

    @Transactional(readOnly = true)
    public List<EventView> list(CurrentUser user, String type) {
        return events.findAllByOrderByEventDateDesc().stream().filter(e -> type == null || type.equals(e.getEventType()))
                .map(e -> view(user, e, false)).toList();
    }

    @Transactional(readOnly = true)
    public EventView get(CurrentUser user, UUID id) {
        ScreeningEvent e = requireEvent(id);
        EventView v = view(user, e, true);
        audit.record("SCREENING_EVENT_READ", "SCREENING_EVENT", id.toString(), AuditService.Outcome.SUCCESS,
                clinical(user) ? "with results" : "logistics only");
        return v;
    }

    ScreeningEvent requireEvent(UUID id) {
        return events.findById(id).orElseThrow(() -> ApiException.notFound("Screening event"));
    }

    static boolean clinical(CurrentUser user) {
        return user != null && user.hasAnyRole(Roles.CLINICIANS);
    }

    EventView view(CurrentUser user, ScreeningEvent e, boolean detail) {
        List<ScreeningParticipant> ps = participants.findByEventIdOrderByAddedAtAsc(e.getId());
        boolean oh = clinical(user);
        Map<String, ScreeningResult> byEmployee = new LinkedHashMap<>();
        if (oh) {
            results.findByEventIdOrderByRecordedAtAsc(e.getId()).forEach(r -> byEmployee.put(r.getEmployeeId(), r));
        }
        int screened = (int) ps.stream().filter(p -> "SCREENED".equals(p.getStatus())).count();
        Integer abnormal = oh ? (int) byEmployee.values().stream().filter(r -> "ABNORMAL".equals(r.getOutcome())).count() : null;
        Integer followed = oh ? (int) byEmployee.values().stream().filter(r -> "ABNORMAL".equals(r.getOutcome()) && r.getCaseId() != null).count()
                : null;
        List<ParticipantView> pv = null;
        if (detail && oh) {
            Map<String, Employee> emps = support.employeesById(ps.stream().map(ScreeningParticipant::getEmployeeId).toList());
            pv = ps.stream().map(p -> new ParticipantView(p.getId(), p.getEmployeeId(),
                    Optional.ofNullable(emps.get(p.getEmployeeId())).map(Employee::getDisplayName).orElse(p.getEmployeeId()), p.getUnitCode(),
                    p.getStatus(), p.getAppointmentId(), p.getAppointmentStartsAt(), byEmployee.containsKey(p.getEmployeeId()),
                    byEmployee.containsKey(p.getEmployeeId()) ? resultView(byEmployee.get(p.getEmployeeId())) : null)).toList();
        }
        String campaignTitle = e.getCampaignId() == null ? null : campaigns.findById(e.getCampaignId()).map(Campaign::getTitle).orElse(null);
        String siteName = sites.findById(e.getSiteCode()).map(ClinicSite::getName).orElse(null);
        return new EventView(e.getId(), e.getEventRef(), e.getCampaignId(), campaignTitle, e.getEventType(), e.getSiteCode(), siteName,
                e.getEventDate(), e.getStatus(), ps.size(), screened, abnormal, followed, pv);
    }

    ResultView resultView(ScreeningResult r) {
        return new ResultView(r.getId(), r.getEventId(), r.getEmployeeId(), support.displayName(r.getEmployeeId()), measures(r),
                PromotionSupport.csv(r.getFlags()), r.getOutcome(), "ABNORMAL".equals(r.getOutcome()), r.getCaseId(), r.getCaseRef(),
                r.getCaseId(), r.getCaseRef(), r.getFollowUpDueAt(), r.getFollowUpOpenedAt(),
                r.getLetterDocumentId(), r.getRecordedBy(), r.getRecordedAt());
    }

    public Map<String, BigDecimal> measures(ScreeningResult r) {
        if (r.getMeasures() == null || r.getMeasures().isBlank()) {
            return Map.of();
        }
        return support.read(r.getMeasures(), new TypeReference<LinkedHashMap<String, BigDecimal>>() {
        });
    }

    // ------------------------------------------------------------------ participants & booking

    @Transactional
    public AddResult addParticipants(CurrentUser user, UUID eventId, ParticipantsCommand cmd) {
        ScreeningEvent e = requireEvent(eventId);
        if (!"PLANNED".equals(e.getStatus())) {
            throw ApiException.conflict("event_closed", "Participants can only be added to a planned event");
        }
        Campaign campaign = e.getCampaignId() == null ? null : campaigns.findById(e.getCampaignId()).orElse(null);
        int added = 0;
        int already = 0;
        int booked = 0;
        List<String> unknown = new ArrayList<>();
        for (String id : new LinkedHashSet<>(cmd.employeeIds())) {
            Employee emp = support.employee(id).filter(Employee::isActive).orElse(null);
            if (emp == null) {
                unknown.add(id);
                continue;
            }
            if (participants.findByEventIdAndEmployeeId(eventId, id).isPresent()) {
                already++;
                continue;
            }
            ScreeningParticipant p = new ScreeningParticipant();
            p.setId(UUID.randomUUID());
            p.setEventId(eventId);
            p.setEmployeeId(id);
            p.setUnitCode(emp.getDepartmentCode());
            p.setStatus("INVITED");
            p.setAddedBy(user.username());
            p.setAddedAt(support.now());
            if (book(user.username(), e, p)) {
                booked++;
            }
            participants.save(p);
            if (campaign != null && "ACTIVE".equals(campaign.getStatus())) {
                campaignService.ensureInvited(campaign, emp);
            }
            support.notifyEmployee(id, "campaign.invitation", Map.of("reference", "a health check (" + e.getEventRef() + ")",
                    "site", e.getSiteCode()), "/#/campaigns");
            added++;
        }
        audit.record("SCREENING_PARTICIPANTS_ADD", "SCREENING_EVENT", eventId.toString(), AuditService.Outcome.SUCCESS,
                "added=" + added + " booked=" + booked + " unknown=" + unknown.size());
        return new AddResult(added, already, booked, unknown, view(user, e, true));
    }

    private boolean book(String actor, ScreeningEvent e, ScreeningParticipant p) {
        AppointmentBooking b = booking.getIfAvailable();
        if (b == null) {
            return false;
        }
        String service = support.configString("promotion.screening.service." + e.getEventType()).orElse("NURSE_REVIEW");
        try {
            Optional<AppointmentBooking.Booking> r = b.bookNext(actor, new AppointmentBooking.BookingRequest(p.getEmployeeId(), service, e.getSiteCode(),
                    e.getEventDate().isBefore(support.today()) ? support.today() : e.getEventDate(), null, SOURCE_TYPE, p.getId().toString(), null));
            if (r.isPresent()) {
                p.setStatus("BOOKED");
                p.setAppointmentId(r.get().appointmentId());
                p.setAppointmentStartsAt(r.get().startsAt());
                return true;
            }
        } catch (ApiException ex) {
            // no capacity / unknown service: the participant stays invited (booked manually at the event)
        }
        return false;
    }

    // ------------------------------------------------------------------ results, thresholds & follow-up

    public Map<String, ScreeningThresholds.Threshold> thresholds() {
        return ScreeningThresholds.resolve(m -> support.configString("promotion.screening.threshold." + m));
    }

    @Transactional
    public ResultView recordResult(CurrentUser user, UUID eventId, ResultCommand cmd) {
        ScreeningEvent e = requireEvent(eventId);
        records.requireClinical(user, cmd.employeeId(), DataClassification.CLINICAL, AccessPolicy.Action.CREATE, "SCREENING_RESULT_RECORD");
        if ("CANCELLED".equals(e.getStatus())) {
            throw ApiException.conflict("event_cancelled", "The screening event was cancelled");
        }
        if (e.getEventDate().isAfter(support.today().plusDays(1))) {
            throw PromotionSupport.unprocessable("event_not_started", "Results can be recorded from the day of the event");
        }
        ScreeningParticipant p = participants.findByEventIdAndEmployeeId(eventId, cmd.employeeId())
                .orElseThrow(() -> PromotionSupport.unprocessable("not_registered", "The employee is not a participant of this event"));
        if (results.existsByEventIdAndEmployeeId(eventId, cmd.employeeId())) {
            throw ApiException.conflict("result_exists", "A result was already recorded for this participant");
        }
        Map<String, BigDecimal> measures = cmd.measures().asMap();
        if (measures.isEmpty()) {
            throw ApiException.badRequest("no_measures", "At least one measure is required");
        }
        List<String> flags = ScreeningThresholds.evaluate(measures, thresholds());
        Instant now = support.now();
        ScreeningResult draft = new ScreeningResult();
        draft.setId(UUID.randomUUID());
        draft.setEventId(eventId);
        draft.setParticipantId(p.getId());
        draft.setEmployeeId(cmd.employeeId());
        draft.setUnitCode(p.getUnitCode());
        draft.setMeasures(support.write(measures));
        draft.setFlags(PromotionSupport.joinCsv(flags));
        draft.setOutcome(ScreeningThresholds.outcome(flags));
        draft.setRecordedBy(user.username());
        draft.setRecordedAt(now);
        ScreeningResult r = results.save(draft);
        p.setStatus("SCREENED");
        if (e.getCampaignId() != null) {
            campaigns.findById(e.getCampaignId()).filter(c -> "ACTIVE".equals(c.getStatus()) || "COMPLETED".equals(c.getStatus()))
                    .ifPresent(c -> campaignService.record(c, support.requireEmployee(cmd.employeeId()), "SCREENING_" + e.getEventType(),
                            now, null, "SCREENING", r.getId().toString()));
        }
        if ("ABNORMAL".equals(r.getOutcome())) {
            followUp(r, e, user.displayName() == null ? user.username() : user.displayName(), user.username());
        }
        audit.record("SCREENING_RESULT_RECORD", "SCREENING_RESULT", r.getId().toString(), AuditService.Outcome.SUCCESS,
                "employee=" + cmd.employeeId() + " outcome=" + r.getOutcome());
        return resultView(r);
    }

    /** Opens the follow-up case (idempotent per result), due task within the configured hours, result letter, notification. */
    void followUp(ScreeningResult r, ScreeningEvent e, String clinicianName, String actor) {
        int hours = support.configInt("promotion.screening.follow-up-hours", 48);
        Instant now = support.now();
        Instant due = r.getRecordedAt().plus(Math.max(1, Math.min(hours, 48)), ChronoUnit.HOURS);
        OhCase c = cases.openIdempotent(OpenCaseCommand.builder(r.getEmployeeId(), CaseCategory.ILLNESS, CaseSource.SYSTEM_TRIGGER)
                .caseType(CaseType.SURVEILLANCE_FOLLOW_UP).sourceRef("screening:" + r.getId()).build());
        r.setCaseId(c.getId());
        r.setCaseRef(c.getCaseRef());
        r.setFollowUpOpenedAt(now);
        r.setFollowUpDueAt(due.isBefore(now) ? now.plus(1, ChronoUnit.HOURS) : due);
        work.systemTask(c.getId(), CaseTask.Type.FOLLOW_UP, "Screening follow-up (" + e.getEventType() + ", " + e.getEventRef() + ")",
                r.getFollowUpDueAt(), null, "system");
        timeline.record(c.getId(), "SCREENING_FOLLOW_UP", "Follow-up opened for an abnormal " + e.getEventType() + " screening result ("
                + e.getEventRef() + ")");
        Employee emp = support.requireEmployee(r.getEmployeeId());
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("employeeName", emp.getDisplayName());
        fields.put("testName", label(e.getEventType()));
        fields.put("performedOn", LocalDate.ofInstant(r.getRecordedAt(), java.time.ZoneOffset.UTC).toString());
        fields.put("resultSummary", summary(measures(r), PromotionSupport.csv(r.getFlags())));
        fields.put("interpretation", "One or more of your results is outside the expected range. Occupational Health will discuss it with you.");
        fields.put("action", "Please book a follow-up appointment with Occupational Health within the next two days.");
        fields.put("clinician", clinicianName == null ? "Occupational Health" : clinicianName);
        DocumentService.DocumentView letter = documents.generateSigned(actor, "RESULT_LETTER", r.getEmployeeId(), c.getId(), fields);
        documents.shareAsSystem(actor, letter.id(), DocumentShare.Target.EMPLOYEE);
        r.setLetterDocumentId(letter.id());
        support.notifyEmployee(r.getEmployeeId(), "screening.follow-up", Map.of("reference", c.getCaseRef()), "/#/campaigns");
        bus.publish(new E12Events.ScreeningFollowUpOpened(r.getId().toString(), r.getEmployeeId(), c.getId().toString()));
        audit.recordAs("system", "MODULE", "SCREENING_FOLLOW_UP_OPENED", "SCREENING_RESULT", r.getId().toString(), AuditService.Outcome.SUCCESS,
                "case=" + c.getCaseRef());
    }

    static String label(String type) {
        return switch (type) {
            case "BIOMETRIC" -> "Biometric health check";
            case "MSK" -> "Musculoskeletal screening";
            case "HEARING" -> "Hearing screening";
            case "VISION" -> "Vision screening";
            default -> "Wellbeing check";
        };
    }

    static String summary(Map<String, BigDecimal> measures, List<String> flags) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, BigDecimal> m : measures.entrySet()) {
            if (!sb.isEmpty()) {
                sb.append("; ");
            }
            sb.append(m.getKey()).append(' ').append(m.getValue().stripTrailingZeros().toPlainString());
        }
        if (!flags.isEmpty()) {
            sb.append(" (outside range: ").append(String.join(", ", flags)).append(')');
        }
        return sb.isEmpty() ? "Recorded in your Occupational Health record" : sb.toString().strip();
    }

    /** Opens the follow-up of an abnormal result that has none yet (demo seeding, manual repair); no-op otherwise. */
    @Transactional
    public void openFollowUp(UUID resultId, String actor, String clinicianName) {
        results.findById(resultId).filter(r -> "ABNORMAL".equals(r.getOutcome()) && r.getCaseId() == null)
                .ifPresent(r -> events.findById(r.getEventId()).ifPresent(e -> followUp(r, e, clinicianName, actor)));
    }

    /** Safety net of the {@code screening-follow-up} job: abnormal results that have no follow-up case yet. */
    @Transactional
    public int sweepFollowUps() {
        int n = 0;
        for (ScreeningResult r : results.findAbnormalWithoutFollowUp()) {
            ScreeningEvent e = events.findById(r.getEventId()).orElse(null);
            if (e != null) {
                followUp(r, e, null, "system");
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ employee view

    public record MyScreening(UUID eventId, String ref, String type, String siteCode, String siteName, LocalDate date, String status,
                              UUID appointmentId, Instant appointmentStartsAt) {
    }

    @Transactional(readOnly = true)
    public List<MyScreening> mine(String employeeId) {
        List<MyScreening> out = new ArrayList<>();
        AppointmentBooking b = booking.getIfAvailable();
        for (ScreeningParticipant p : participants.findByEmployeeId(employeeId)) {
            ScreeningEvent e = events.findById(p.getEventId()).orElse(null);
            if (e == null || "CANCELLED".equals(e.getStatus())) {
                continue;
            }
            UUID appt = p.getAppointmentId();
            Instant starts = p.getAppointmentStartsAt();
            if (b != null && appt != null) {
                Optional<AppointmentBooking.Booking> live = b.bookingsFor(SOURCE_TYPE, p.getId().toString()).stream().findFirst();
                appt = live.map(AppointmentBooking.Booking::appointmentId).orElse(null);
                starts = live.map(AppointmentBooking.Booking::startsAt).orElse(null);
            }
            out.add(new MyScreening(e.getId(), e.getEventRef(), e.getEventType(), e.getSiteCode(),
                    sites.findById(e.getSiteCode()).map(ClinicSite::getName).orElse(null), e.getEventDate(), p.getStatus(), appt, starts));
        }
        out.sort(java.util.Comparator.comparing(MyScreening::date).reversed());
        return out;
    }
}
