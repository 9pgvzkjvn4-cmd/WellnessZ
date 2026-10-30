package com.wellnessz.e11.exposure;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.CaseService;
import com.wellnessz.casekernel.model.CaseCategory;
import com.wellnessz.casekernel.model.CaseSource;
import com.wellnessz.casekernel.model.CaseTask;
import com.wellnessz.casekernel.model.CaseTaskRepository;
import com.wellnessz.casekernel.model.CaseType;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OpenCaseCommand;
import com.wellnessz.casekernel.model.Urgency;
import com.wellnessz.casekernel.support.CaseTimeline;
import com.wellnessz.casekernel.support.CaseWorkService;
import com.wellnessz.casekernel.support.EmployeeRecordAccess;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.common.model.Roles;
import com.wellnessz.e11.model.Chemical;
import com.wellnessz.e11.model.E11ChemicalRepository;
import com.wellnessz.e11.model.E11ExposureRepository;
import com.wellnessz.e11.model.E11ExposureTaskRepository;
import com.wellnessz.e11.model.E11PathwayRepository;
import com.wellnessz.e11.model.Exposure;
import com.wellnessz.e11.model.ExposureTask;
import com.wellnessz.e11.model.Pathway;
import com.wellnessz.e11.support.TravelHealthSupport;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.notification.Priority;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

import tools.jackson.core.type.TypeReference;

/**
 * Exposure pathways with protocol timers (E11-F03): an exposure opens a kernel EXPOSURE case, its pathway template creates the
 * time-critical tasks (also as kernel case tasks) and the serology schedule; chemical exposures link the SDS from the chemical
 * register. Exposures arrive from clinicians or automatically from the EHS incident feed. The {@code exposure-timers} job
 * alerts OH once per task and level.
 */
@Service
public class ExposurePathwayService {

    public static final String TIMER = "TIMER";
    public static final String SEROLOGY = "SEROLOGY";
    static final String INCIDENT_ACTOR = "ehs-incident-feed";
    static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final Logger log = LoggerFactory.getLogger(ExposurePathwayService.class);

    private final E11PathwayRepository pathways;
    private final E11ChemicalRepository chemicals;
    private final E11ExposureRepository exposures;
    private final E11ExposureTaskRepository tasks;
    private final CaseTaskRepository caseTasks;
    private final CaseService caseService;
    private final CaseWorkService caseWork;
    private final CaseTimeline timeline;
    private final EmployeeRecordAccess records;
    private final TravelHealthSupport support;
    private final AuditService audit;

    public ExposurePathwayService(E11PathwayRepository pathways, E11ChemicalRepository chemicals, E11ExposureRepository exposures,
                                  E11ExposureTaskRepository tasks, CaseTaskRepository caseTasks, CaseService caseService, CaseWorkService caseWork,
                                  CaseTimeline timeline, EmployeeRecordAccess records, TravelHealthSupport support, AuditService audit) {
        this.pathways = pathways;
        this.chemicals = chemicals;
        this.exposures = exposures;
        this.tasks = tasks;
        this.caseTasks = caseTasks;
        this.caseService = caseService;
        this.caseWork = caseWork;
        this.timeline = timeline;
        this.records = records;
        this.support = support;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ templates

    public record PathwayView(String code, String name, String description, List<ProtocolTimers.TimerDef> timers,
                              List<ProtocolTimers.SerologyDef> serology) {
    }

    public record ChemicalView(String code, String name, String unNumber, String sdsUrl, List<String> hazards) {
    }

    List<ProtocolTimers.TimerDef> timers(Pathway p) {
        return support.read(p.getTimers(), new TypeReference<List<ProtocolTimers.TimerDef>>() {
        }, List.of());
    }

    List<ProtocolTimers.SerologyDef> serology(Pathway p) {
        return support.read(p.getSerology(), new TypeReference<List<ProtocolTimers.SerologyDef>>() {
        }, List.of());
    }

    PathwayView view(Pathway p) {
        return new PathwayView(p.getCode(), p.getName(), p.getDescription(), timers(p), serology(p));
    }

    static ChemicalView view(Chemical c) {
        return new ChemicalView(c.getCode(), c.getName(), c.getUnNumber(), c.getSdsUrl(), TravelHealthSupport.csv(c.getHazards()));
    }

    @Transactional(readOnly = true)
    public List<PathwayView> pathways() {
        return pathways.findByActiveTrueOrderByCodeAsc().stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public List<ChemicalView> chemicals() {
        return chemicals.findAllByOrderByNameAsc().stream().map(ExposurePathwayService::view).toList();
    }

    // ------------------------------------------------------------------ exposures

    public record CreateCommand(@NotBlank @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String employeeId,
                                @NotBlank @Pattern(regexp = "[A-Z0-9_]{2,40}") String pathway, @NotNull Instant occurredAt,
                                @Size(max = 4000) String details, @Pattern(regexp = "[A-Z0-9_]{2,40}") String chemicalCode,
                                @Pattern(regexp = "[A-Z0-9]{2,10}") String flightRef, Boolean sourceKnown) {
    }

    public record CompleteCommand(@Size(max = 2000) String note) {
    }

    public record TimerView(String code, String label, Instant dueAt, String status, Instant completedAt, String completedBy, Boolean withinWindow) {
    }

    public record SerologyView(String code, String label, LocalDate dueOn, boolean done, String status, Instant completedAt) {
    }

    public record ExposureView(UUID id, String ref, UUID caseId, String caseRef, String employeeId, String displayName, String pathway,
                               String pathwayName, Instant occurredAt, String details, ChemicalView chemical, String flightRef, Boolean sourceKnown,
                               UUID incidentId, String status, List<TimerView> timers, List<SerologyView> serology, String createdBy,
                               Instant createdAt) {
    }

    /** Exposure status: OVERDUE when any timer / serology is overdue, OPEN while anything is pending, otherwise CLOSED. */
    static String status(List<TimerView> timers, List<SerologyView> serology) {
        boolean overdue = timers.stream().anyMatch(t -> ProtocolTimers.OVERDUE.equals(t.status()))
                || serology.stream().anyMatch(s -> ProtocolTimers.OVERDUE.equals(s.status()));
        if (overdue) {
            return "OVERDUE";
        }
        boolean pending = timers.stream().anyMatch(t -> ProtocolTimers.PENDING.equals(t.status())) || serology.stream().anyMatch(s -> !s.done());
        return pending ? "OPEN" : "CLOSED";
    }

    ExposureView view(Exposure x, List<ExposureTask> taskList, String displayName) {
        Instant now = support.now();
        LocalDate today = support.today();
        List<TimerView> timers = new ArrayList<>();
        List<SerologyView> serology = new ArrayList<>();
        for (ExposureTask t : taskList) {
            if (TIMER.equals(t.getKind())) {
                timers.add(new TimerView(t.getCode(), t.getLabel(), t.getDueAt(), ProtocolTimers.status(t.getDueAt(), t.getCompletedAt(), now),
                        t.getCompletedAt(), t.getCompletedBy(), t.getCompletedAt() == null ? null : ProtocolTimers.withinWindow(t.getDueAt(), t.getCompletedAt())));
            } else {
                boolean done = t.getCompletedAt() != null;
                serology.add(new SerologyView(t.getCode(), t.getLabel(), t.getDueOn(), done, ProtocolTimers.status(t.getDueOn(), done, today),
                        t.getCompletedAt()));
            }
        }
        Pathway p = pathways.findById(x.getPathway()).orElse(null);
        ChemicalView chem = x.getChemicalCode() == null ? null : chemicals.findById(x.getChemicalCode()).map(ExposurePathwayService::view).orElse(null);
        return new ExposureView(x.getId(), x.getExpRef(), x.getCaseId(), x.getCaseRef(), x.getEmployeeId(), displayName, x.getPathway(),
                p == null ? x.getPathway() : p.getName(), x.getOccurredAt(), x.getDetails(), chem, x.getFlightRef(), x.getSourceKnown(),
                x.getIncidentId(), status(timers, serology), timers, serology, x.getCreatedBy(), x.getCreatedAt());
    }

    ExposureView view(Exposure x) {
        return view(x, tasks.findByExposureIdOrderByPositionAsc(x.getId()), support.displayName(x.getEmployeeId()));
    }

    @Transactional
    public ExposureView create(CurrentUser user, CreateCommand cmd) {
        Employee e = records.requireClinical(user, cmd.employeeId(), DataClassification.CLINICAL, AccessPolicy.Action.CREATE, "EXPOSURE_CREATE");
        Pathway p = pathways.findById(cmd.pathway()).filter(Pathway::isActive)
                .orElseThrow(() -> ApiException.badRequest("unknown_pathway", "Unknown exposure pathway"));
        Instant now = support.now();
        if (cmd.occurredAt().isAfter(now.plusSeconds(300)) || cmd.occurredAt().isBefore(now.minusSeconds(366L * 86_400))) {
            throw ApiException.badRequest("invalid_occurred_at", "The exposure time must be within the last year and not in the future");
        }
        if (cmd.chemicalCode() != null && !chemicals.existsById(cmd.chemicalCode())) {
            throw ApiException.badRequest("unknown_chemical", "Unknown chemical – add it to the chemical register first");
        }
        UUID id = UUID.randomUUID();
        Exposure x = open(id, user.username(), e, p, cmd.occurredAt(), TravelHealthSupport.clinical("details", cmd.details(), 4000), cmd.chemicalCode(),
                cmd.flightRef(), cmd.sourceKnown(), null, CaseSource.SYSTEM_TRIGGER, "e11-exposure:" + id, null);
        audit.record("EXPOSURE_CREATE", "EXPOSURE", x.getId().toString(), AuditService.Outcome.SUCCESS, p.getCode());
        return view(x);
    }

    Exposure open(UUID id, String actor, Employee e, Pathway p, Instant occurredAt, String details, String chemicalCode, String flightRef,
                  Boolean sourceKnown, UUID incidentId, CaseSource source, String sourceRef, String incidentRef) {
        Instant now = support.now();
        OhCase kernel = caseService.openIdempotent(OpenCaseCommand.builder(e.getEmployeeId(), CaseCategory.EXPOSURE, source)
                .caseType(CaseType.EXPOSURE).sourceRef(sourceRef).incidentRef(incidentRef)
                .occurredOn(LocalDate.ofInstant(occurredAt, ZoneOffset.UTC)).urgency(Urgency.URGENT).templateCode(p.getCode()).build());
        Exposure x = new Exposure();
        x.setId(id);
        x.setExpRef(support.nextRef("EXP"));
        x.setCaseId(kernel.getId());
        x.setCaseRef(kernel.getCaseRef());
        x.setEmployeeId(e.getEmployeeId());
        x.setPathway(p.getCode());
        x.setOccurredAt(occurredAt);
        x.setDetails(details);
        x.setChemicalCode(chemicalCode);
        x.setFlightRef(flightRef);
        x.setSourceKnown(sourceKnown);
        x.setIncidentId(incidentId);
        x.setCreatedBy(actor);
        x.setCreatedAt(now);
        x.setUpdatedAt(now);
        exposures.save(x);
        int position = 0;
        for (ProtocolTimers.TimerDef t : timers(p)) {
            Instant due = ProtocolTimers.dueAt(occurredAt, t.offsetMinutes());
            CaseTask ct = caseWork.systemTask(kernel.getId(), CaseTask.Type.FOLLOW_UP, t.label(), due, null, actor);
            tasks.save(task(x.getId(), TIMER, t.code(), t.label(), position++, due, null, ct.getId()));
        }
        for (ProtocolTimers.SerologyDef s : serology(p)) {
            tasks.save(task(x.getId(), SEROLOGY, s.code(), s.label(), position++, null, ProtocolTimers.dueOn(occurredAt, s.offset()), null));
        }
        timeline.record(kernel.getId(), "EXPOSURE_PATHWAY", "Exposure pathway " + p.getCode() + " started (" + x.getExpRef() + ")");
        return x;
    }

    static ExposureTask task(UUID exposureId, String kind, String code, String label, int position, Instant dueAt, LocalDate dueOn, UUID caseTaskId) {
        ExposureTask t = ExposureTask.create();
        t.setExposureId(exposureId);
        t.setKind(kind);
        t.setCode(code);
        t.setLabel(label);
        t.setPosition(position);
        t.setDueAt(dueAt);
        t.setDueOn(dueOn);
        t.setStatus(ProtocolTimers.PENDING);
        t.setCaseTaskId(caseTaskId);
        return t;
    }

    Exposure require(CurrentUser user, UUID id, AccessPolicy.Action action, String auditAction) {
        Exposure x = exposures.findById(id).orElse(null);
        if (x == null) {
            audit.record(auditAction, "EXPOSURE", id.toString(), AuditService.Outcome.DENIED, "Unknown exposure");
            throw ApiException.notFound("Exposure");
        }
        records.requireClinical(user, x.getEmployeeId(), DataClassification.CLINICAL, action, auditAction);
        return x;
    }

    @Transactional(readOnly = true)
    public ExposureView get(CurrentUser user, UUID id) {
        Exposure x = require(user, id, AccessPolicy.Action.READ, "EXPOSURE_READ");
        audit.record("EXPOSURE_READ", "EXPOSURE", id.toString(), AuditService.Outcome.SUCCESS, null);
        return view(x);
    }

    @Transactional(readOnly = true)
    public List<ExposureView> list(CurrentUser user, String status) {
        List<Exposure> all = exposures.findTop500ByOrderByOccurredAtDesc();
        Map<String, Employee> people = support.employeesById(all.stream().map(Exposure::getEmployeeId).distinct().toList());
        Map<UUID, List<ExposureTask>> byExposure = new LinkedHashMap<>();
        if (!all.isEmpty()) {
            tasks.findByExposureIdInOrderByPositionAsc(all.stream().map(Exposure::getId).toList())
                    .forEach(t -> byExposure.computeIfAbsent(t.getExposureId(), k -> new ArrayList<>()).add(t));
        }
        List<ExposureView> out = new ArrayList<>();
        for (Exposure x : all) {
            Employee e = people.get(x.getEmployeeId());
            if (e == null || !records.canSeeClinical(user, e, DataClassification.CLINICAL)) {
                continue;
            }
            ExposureView v = view(x, byExposure.getOrDefault(x.getId(), List.of()), e.getDisplayName());
            if (status == null || status.equals(v.status()) || "OPEN".equals(status) && "OVERDUE".equals(v.status())) {
                out.add(v);
            }
        }
        audit.record("EXPOSURE_LIST", "EXPOSURE", null, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    @Transactional
    public ExposureView complete(CurrentUser user, UUID id, String code, CompleteCommand cmd) {
        Exposure x = require(user, id, AccessPolicy.Action.UPDATE, "EXPOSURE_TASK_COMPLETE");
        ExposureTask t = tasks.findByExposureIdAndCode(x.getId(), code).orElseThrow(() -> ApiException.notFound("Exposure task"));
        if (ProtocolTimers.DONE.equals(t.getStatus())) {
            throw ApiException.conflict("already_done", "This task is already completed");
        }
        Instant now = support.now();
        t.setStatus(ProtocolTimers.DONE);
        t.setCompletedAt(now);
        t.setCompletedBy(user.username());
        t.setNote(TravelHealthSupport.clinical("note", cmd == null ? null : cmd.note(), 2000));
        if (t.getCaseTaskId() != null) {
            caseTasks.findById(t.getCaseTaskId()).filter(ct -> ct.getStatus() == CaseTask.Status.OPEN).ifPresent(ct -> {
                ct.complete(now);
                caseTasks.save(ct);
            });
        }
        x.setUpdatedAt(now);
        timeline.record(x.getCaseId(), "EXPOSURE_TASK_DONE", t.getLabel() + (TIMER.equals(t.getKind()) && t.getDueAt() != null
                ? ProtocolTimers.withinWindow(t.getDueAt(), now) ? " – within the protocol window" : " – after the protocol window" : ""));
        audit.record("EXPOSURE_TASK_COMPLETE", "EXPOSURE", id.toString(), AuditService.Outcome.SUCCESS, code);
        return view(x);
    }

    // ------------------------------------------------------------------ incident feed

    /** Maps the incident feed's exposure type to a pathway: NEEDLESTICK / CHEMICAL / FUME / HEAT (and bite / sting). */
    public static Optional<String> pathwayFor(String exposureType) {
        if (exposureType == null || exposureType.isBlank()) {
            return Optional.empty();
        }
        String t = exposureType.toUpperCase(Locale.ROOT);
        if (t.contains("NEEDLE") || t.contains("SHARPS") || t.contains("BODY_FLUID") || t.contains("BBP")) {
            return Optional.of("NEEDLESTICK_BBP");
        }
        if (t.contains("CHEMICAL") || t.contains("DGR")) {
            return Optional.of("CHEMICAL_DGR");
        }
        if (t.contains("FUME") || t.contains("SMOKE") || t.contains("CABIN_AIR")) {
            return Optional.of("CABIN_FUME");
        }
        if (t.contains("HEAT")) {
            return Optional.of("HEAT_ILLNESS");
        }
        if (t.contains("BITE") || t.contains("STING")) {
            return Optional.of("BITE_STING");
        }
        return Optional.empty();
    }

    /** Facts of {@code ehs.incident.received.v1} used here. */
    public record IncidentInput(UUID incidentId, String externalRef, String employeeId, String exposureType, Instant occurredAt) {
    }

    /** Creates the pathway for an incident (idempotent on the incident id); empty when the incident is not an exposure. */
    @Transactional
    public Optional<UUID> fromIncident(IncidentInput in) {
        Optional<String> code = pathwayFor(in.exposureType());
        if (code.isEmpty() || in.employeeId() == null || in.employeeId().isBlank()) {
            return Optional.empty();
        }
        if (exposures.existsByIncidentId(in.incidentId())) {
            return Optional.empty();
        }
        Employee e = support.employee(in.employeeId()).orElse(null);
        Pathway p = pathways.findById(code.get()).filter(Pathway::isActive).orElse(null);
        if (e == null || p == null) {
            log.info("Exposure incident {} not started: employee or pathway unknown", in.incidentId());
            return Optional.empty();
        }
        Instant now = support.now();
        Instant occurred = in.occurredAt() == null || in.occurredAt().isAfter(now) ? now : in.occurredAt();
        Exposure x = open(UUID.randomUUID(), INCIDENT_ACTOR, e, p, occurred, null, null, null, null, in.incidentId(), CaseSource.EHS_INCIDENT,
                in.incidentId().toString(), in.externalRef());
        audit.recordAs(INCIDENT_ACTOR, "SYSTEM", "EXPOSURE_FROM_INCIDENT", "EXPOSURE", x.getId().toString(), AuditService.Outcome.SUCCESS, p.getCode());
        return Optional.of(x.getId());
    }

    // ------------------------------------------------------------------ timer alerts (job)

    /** Alerts due-soon / overdue protocol timers once per task and level; returns the number of alerts. */
    @Transactional
    public int alertTimers(Instant now) {
        int before = support.configInt("travelhealth.exposure.alert-before-minutes", 30);
        int sent = 0;
        Map<UUID, Exposure> cache = new LinkedHashMap<>();
        for (ExposureTask t : tasks.findByKindAndStatusAndDueAtBefore(TIMER, ProtocolTimers.PENDING, now.plusSeconds(before * 60L + 1))) {
            Optional<ProtocolTimers.Alert> alert = ProtocolTimers.alert(t.getDueAt(), now, before, t.getDueSoonAlertedAt() != null,
                    t.getOverdueAlertedAt() != null);
            if (alert.isEmpty()) {
                continue;
            }
            Exposure x = cache.computeIfAbsent(t.getExposureId(), k -> exposures.findById(k).orElse(null));
            if (x == null) {
                continue;
            }
            boolean overdue = alert.get() == ProtocolTimers.Alert.OVERDUE;
            support.notifyOwnerOrRole(INCIDENT_ACTOR.equals(x.getCreatedBy()) ? null : x.getCreatedBy(), Roles.OH_CLINICIAN, "exposure.timer-alert",
                    Map.of("caseRef", x.getCaseRef(), "appointmentTime", HHMM.format(t.getDueAt()), "level", overdue ? "overdue" : "due soon"),
                    "/exposures/" + x.getId(), Priority.URGENT);
            if (overdue) {
                t.setOverdueAlertedAt(now);
                if (t.getDueSoonAlertedAt() == null) {
                    t.setDueSoonAlertedAt(now);
                }
            } else {
                t.setDueSoonAlertedAt(now);
            }
            sent++;
        }
        return sent;
    }
}
