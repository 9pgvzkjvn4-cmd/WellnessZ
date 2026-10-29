package com.wellnessz.e04.plan;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.model.CaseTask;
import com.wellnessz.casekernel.model.CaseTaskRepository;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.support.AppointmentBooking;
import com.wellnessz.casekernel.support.AppointmentStatusListener;
import com.wellnessz.casekernel.support.CaseTimeline;
import com.wellnessz.casekernel.support.CaseWorkService;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.documents.DocumentService;
import com.wellnessz.documents.model.DocumentShare;
import com.wellnessz.e02.model.DurationGuideline;
import com.wellnessz.e02.model.DurationGuidelineRepository;
import com.wellnessz.e04.E04Events;
import com.wellnessz.e04.model.PlanFollowUp;
import com.wellnessz.e04.model.PlanFollowUpRepository;
import com.wellnessz.e04.model.PlanTemplate;
import com.wellnessz.e04.model.PlanTemplateRepository;
import com.wellnessz.e04.model.Referral;
import com.wellnessz.e04.model.ServiceDefinition;
import com.wellnessz.e04.model.ServiceDefinitionRepository;
import com.wellnessz.e04.model.TreatmentPlan;
import com.wellnessz.e04.model.TreatmentPlanRepository;
import com.wellnessz.e04.model.TreatmentReferralRepository;
import com.wellnessz.e04.model.VarianceAlert;
import com.wellnessz.e04.model.VarianceAlertRepository;
import com.wellnessz.e04.support.TreatmentAccess;
import com.wellnessz.e04.support.TreatmentSpis;
import com.wellnessz.e04.support.TreatmentSupport;
import com.wellnessz.integration.outbox.EventBus;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Treatment plan & follow-up scheduler (E04-F01): template-based, versioned plans; follow-ups as kernel case tasks and
 * (optionally) clinic bookings through the scheduling SPI; employee confirmations; expected-recovery variance alerts
 * against the duration guideline; printable plan document shared with the employee.
 */
@Service
public class PlanService {

    public static final String SOURCE_TYPE = "TREATMENT_FOLLOW_UP";
    static final Set<String> OPEN_FOLLOW_UP = Set.of("PLANNED", "BOOKED", "CONFIRMED");
    static final Set<String> OPEN_ALERT = Set.of("OPEN", "ACKNOWLEDGED", "ESCALATED");

    private final PlanTemplateRepository templates;
    private final TreatmentPlanRepository plans;
    private final PlanFollowUpRepository followUps;
    private final VarianceAlertRepository alerts;
    private final DurationGuidelineRepository guidelines;
    private final TreatmentReferralRepository referrals;
    private final ServiceDefinitionRepository services;
    private final TreatmentAccess access;
    private final CaseWorkService work;
    private final CaseTaskRepository tasks;
    private final CaseTimeline timeline;
    private final DocumentService documents;
    private final TreatmentSpis spis;
    private final TreatmentSupport support;
    private final EventBus bus;
    private final AuditService audit;

    public PlanService(PlanTemplateRepository templates, TreatmentPlanRepository plans, PlanFollowUpRepository followUps,
                       VarianceAlertRepository alerts, DurationGuidelineRepository guidelines, TreatmentReferralRepository referrals,
                       ServiceDefinitionRepository services, TreatmentAccess access, CaseWorkService work, CaseTaskRepository tasks,
                       CaseTimeline timeline, DocumentService documents, TreatmentSpis spis, TreatmentSupport support, EventBus bus,
                       AuditService audit) {
        this.templates = templates;
        this.plans = plans;
        this.followUps = followUps;
        this.alerts = alerts;
        this.guidelines = guidelines;
        this.referrals = referrals;
        this.services = services;
        this.access = access;
        this.work = work;
        this.tasks = tasks;
        this.timeline = timeline;
        this.documents = documents;
        this.spis = spis;
        this.support = support;
        this.bus = bus;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ commands & views

    public record InterventionInput(@NotBlank @Pattern(regexp = "PHYSIOTHERAPY|MEDICATION|SPECIALIST_REVIEW|IMAGING|SELF_CARE|PSYCHOLOGICAL_THERAPY|OH_REVIEW|OTHER") String type,
                                    @NotBlank @Size(max = 500) String description, @Size(max = 100) String frequency, LocalDate startDate,
                                    LocalDate endDate, @Pattern(regexp = "[A-Z0-9_]{2,40}") String providerCode) {
    }

    public record FollowUpInput(@NotNull LocalDate dueDate, @NotBlank @Pattern(regexp = "OH_REVIEW|PHONE|PHYSIOTHERAPY|SPECIALIST|TELEHEALTH") String type,
                                @Pattern(regexp = "[A-Z0-9][A-Z0-9_\\-]{1,39}") String siteCode, Boolean book) {
    }

    public record PlanCommand(@Pattern(regexp = "[A-Z][A-Z0-9_]{1,39}") String templateCode,
                              @Size(max = 20) List<@NotBlank @Size(max = 500) String> goals,
                              @Valid @Size(max = 30) List<InterventionInput> interventions,
                              @Valid @Size(max = 30) List<FollowUpInput> followUps,
                              @Min(1) @Max(730) Integer expectedRecoveryDays,
                              @Pattern(regexp = "[A-Z][0-9A-Z.]{1,9}") String guidelineCode,
                              @Min(1) @Max(500) Integer varianceThresholdPct) {
    }

    public record TemplateView(String code, String injuryType, String title, List<String> goals, List<Map<String, Object>> interventions,
                               List<Integer> followUpDays, String guidelineCode, Integer expectedDays) {
    }

    public record FollowUpView(UUID id, LocalDate dueDate, String type, String status, String siteCode, UUID appointmentId,
                               String employeeResponse, String responseReason, boolean book) {
    }

    public record Guideline(String code, String title, Integer expectedDays) {
    }

    public record VarianceView(int actualDays, int expectedDays, BigDecimal pctOver, boolean alert) {
    }

    public record HistoryEntry(UUID id, int version, String status, Instant createdAt, String createdBy) {
    }

    public record PlanView(UUID id, UUID caseId, String caseRef, String employeeId, String status, int version, String templateCode,
                           List<String> goals, List<PlanMaths.Intervention> interventions, List<FollowUpView> followUps, LocalDate startDate,
                           Integer expectedRecoveryDays, LocalDate expectedRecoveryDate, Guideline guideline, VarianceView variance,
                           Integer varianceThresholdPct, String responsibleClinician, UUID documentId, Instant createdAt, String createdBy,
                           List<HistoryEntry> history) {
    }

    public record AlertView(UUID id, UUID caseId, String caseRef, String employeeId, String displayName, UUID planId, int expectedDays,
                            int actualDays, BigDecimal pctOver, String status, Instant raisedAt, String actedBy, Instant actedAt) {
    }

    // employee (plain-language) views – no OH-only fields
    public record MyIntervention(String type, String description, String frequency, LocalDate startDate, LocalDate endDate,
                                 String providerName) {
    }

    public record MyFollowUp(UUID id, LocalDate dueDate, String type, String status, UUID appointmentId, Instant appointmentAt,
                             String siteCode, String employeeResponse) {
    }

    public record ExternalAppointment(String ref, String serviceName, String providerCode, String providerName, Instant appointmentAt,
                                      String location, String instructions, String status) {
    }

    public record MyPlan(UUID id, String status, List<String> goals, List<MyIntervention> interventions, List<MyFollowUp> followUps,
                         LocalDate expectedRecoveryDate, List<ExternalAppointment> externalAppointments) {
    }

    // ------------------------------------------------------------------ templates

    @Transactional(readOnly = true)
    public List<TemplateView> templates() {
        return templates.findByActiveTrueOrderByCode().stream().map(this::view).toList();
    }

    TemplateView view(PlanTemplate t) {
        List<Integer> days = TreatmentSupport.csv(t.getFollowUpDays()).stream().filter(s -> s.matches("\\d{1,3}")).map(Integer::valueOf).toList();
        return new TemplateView(t.getCode(), t.getInjuryType(), t.getTitle(), support.readStrings(t.getGoals()),
                support.readMaps(t.getInterventions()), days, t.getGuidelineCode(), t.getExpectedDays());
    }

    // ------------------------------------------------------------------ create / update

    @Transactional
    public PlanView create(CurrentUser user, UUID caseId, PlanCommand cmd, boolean activate) {
        OhCase c = access.clinical(user, caseId, AccessPolicy.Action.UPDATE, "TREATMENT_PLAN_CREATE");
        if (activate && hasActive(caseId, null)) {
            throw ApiException.conflict("active_plan_exists", "The case already has an active treatment plan – edit it instead");
        }
        Instant now = support.now();
        TreatmentPlan p = new TreatmentPlan();
        p.setId(UUID.randomUUID());
        p.setSeriesId(p.getId());
        p.setVersion(1);
        p.setCaseId(c.getId());
        p.setEmployeeId(c.getEmployeeId());
        p.setStatus("DRAFT");
        p.setResponsibleClinician(user.username());
        p.setCreatedBy(user.username());
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        p.setStartDate(support.today());
        List<FollowUpInput> fus = apply(p, cmd);
        p = plans.save(p);
        for (FollowUpInput f : fus) {
            addFollowUp(p, f.dueDate(), f.type(), f.siteCode(), Boolean.TRUE.equals(f.book()));
        }
        timeline.record(c.getId(), "TREATMENT_PLAN_CREATED", "Treatment plan created (version 1)");
        if (activate) {
            activate(p, user.username());
        }
        audit.record("TREATMENT_PLAN_CREATE", "TREATMENT_PLAN", p.getId().toString(), AuditService.Outcome.SUCCESS,
                "case=" + caseId + " status=" + p.getStatus());
        return view(p, c);
    }

    /** New version of a plan: the previous version becomes SUPERSEDED, open follow-ups move to the new version. */
    @Transactional
    public PlanView update(CurrentUser user, UUID planId, PlanCommand cmd) {
        TreatmentPlan prev = plans.findById(planId).orElseThrow(() -> ApiException.notFound("Treatment plan"));
        OhCase c = access.clinical(user, prev.getCaseId(), AccessPolicy.Action.UPDATE, "TREATMENT_PLAN_UPDATE");
        if (!Set.of("DRAFT", "ACTIVE").contains(prev.getStatus())) {
            throw ApiException.conflict("plan_closed", "Only draft or active plans can be edited");
        }
        Instant now = support.now();
        TreatmentPlan p = new TreatmentPlan();
        p.setId(UUID.randomUUID());
        p.setSeriesId(prev.getSeriesId());
        p.setVersion(prev.getVersion() + 1);
        p.setCaseId(prev.getCaseId());
        p.setEmployeeId(prev.getEmployeeId());
        p.setStatus(prev.getStatus());
        p.setTemplateCode(prev.getTemplateCode());
        p.setGoals(prev.getGoals());
        p.setInterventions(prev.getInterventions());
        p.setExpectedRecoveryDays(prev.getExpectedRecoveryDays());
        p.setGuidelineCode(prev.getGuidelineCode());
        p.setVarianceThresholdPct(prev.getVarianceThresholdPct());
        p.setResponsibleClinician(prev.getResponsibleClinician());
        p.setStartDate(prev.getStartDate());
        p.setCreatedBy(user.username());
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        List<FollowUpInput> fus = apply(p, cmd);
        prev.setStatus("SUPERSEDED");
        prev.setUpdatedAt(now);
        plans.save(p);
        List<PlanFollowUp> existing = followUps.findByPlanIdOrderByDueDateAsc(prev.getId());
        for (PlanFollowUp f : existing) {
            f.setPlanId(p.getId());
        }
        for (FollowUpInput f : fus) {
            boolean dup = existing.stream().anyMatch(x -> x.getDueDate().equals(f.dueDate()) && x.getType().equals(f.type())
                    && !"CANCELLED".equals(x.getStatus()));
            if (!dup) {
                PlanFollowUp n = addFollowUp(p, f.dueDate(), f.type(), f.siteCode(), Boolean.TRUE.equals(f.book()));
                if ("ACTIVE".equals(p.getStatus())) {
                    schedule(p, n, user.username());
                }
            }
        }
        alerts.findByPlanIdOrderByRaisedAtDesc(prev.getId()).forEach(a -> a.setPlanId(p.getId()));
        timeline.record(c.getId(), "TREATMENT_PLAN_UPDATED", "Treatment plan updated (version " + p.getVersion() + ")");
        audit.record("TREATMENT_PLAN_UPDATE", "TREATMENT_PLAN", p.getId().toString(), AuditService.Outcome.SUCCESS, "version=" + p.getVersion());
        return view(p, c);
    }

    /** Applies the command (template expansion when lists are omitted) and returns the follow-ups to create. */
    private List<FollowUpInput> apply(TreatmentPlan p, PlanCommand cmd) {
        PlanTemplate t = null;
        if (cmd.templateCode() != null) {
            t = templates.findById(cmd.templateCode()).filter(PlanTemplate::isActive)
                    .orElseThrow(() -> ApiException.badRequest("unknown_template", "Unknown plan template"));
            p.setTemplateCode(t.getCode());
        }
        List<String> goals = cmd.goals() != null && !cmd.goals().isEmpty() ? cmd.goals().stream().map(String::strip).toList()
                : t != null ? support.readStrings(t.getGoals()) : goalsOf(p);
        if (goals.isEmpty()) {
            throw ApiException.badRequest("goals_required", "A treatment plan needs at least one goal");
        }
        List<PlanMaths.Intervention> interventions;
        if (cmd.interventions() != null && !cmd.interventions().isEmpty()) {
            interventions = new ArrayList<>();
            for (InterventionInput i : cmd.interventions()) {
                if (i.startDate() != null && i.endDate() != null && i.endDate().isBefore(i.startDate())) {
                    throw ApiException.badRequest("invalid_dates", "Intervention end date before start date");
                }
                if (i.providerCode() != null) {
                    support.requireActivePartner(i.providerCode(), "providerCode");
                }
                interventions.add(new PlanMaths.Intervention(i.type(), i.description().strip(), i.frequency(), i.startDate(), i.endDate(),
                        i.providerCode()));
            }
        } else if (t != null) {
            interventions = PlanMaths.expandInterventions(support.readMaps(t.getInterventions()), p.getStartDate());
        } else {
            interventions = interventionsOf(p);
        }
        p.setGoals(support.write(goals));
        p.setInterventions(support.write(interventions));
        if (cmd.guidelineCode() != null) {
            guidelines.findById(cmd.guidelineCode()).orElseThrow(() -> ApiException.badRequest("unknown_guideline", "Unknown duration guideline"));
            p.setGuidelineCode(cmd.guidelineCode());
        } else if (t != null && t.getGuidelineCode() != null && p.getGuidelineCode() == null) {
            p.setGuidelineCode(t.getGuidelineCode());
        }
        if (cmd.expectedRecoveryDays() != null) {
            p.setExpectedRecoveryDays(cmd.expectedRecoveryDays());
        } else if (p.getExpectedRecoveryDays() == null && t != null && t.getGuidelineCode() == null) {
            p.setExpectedRecoveryDays(t.getExpectedDays());
        }
        if (cmd.varianceThresholdPct() != null) {
            p.setVarianceThresholdPct(cmd.varianceThresholdPct());
        }
        List<FollowUpInput> fus = new ArrayList<>();
        if (cmd.followUps() != null && !cmd.followUps().isEmpty()) {
            for (FollowUpInput f : cmd.followUps()) {
                if (f.dueDate().isBefore(support.today())) {
                    throw ApiException.badRequest("invalid_due_date", "Follow-up due dates cannot be in the past");
                }
                fus.add(f);
            }
        } else if (t != null && p.getVersion() == 1) {
            for (LocalDate d : PlanMaths.followUpDates(t.getFollowUpDays(), p.getStartDate())) {
                fus.add(new FollowUpInput(d, "OH_REVIEW", null, false));
            }
        }
        return fus;
    }

    List<PlanMaths.Intervention> readInterventions(String json) {
        return support.read(json, new tools.jackson.core.type.TypeReference<List<PlanMaths.Intervention>>() {
        }, List.of());
    }

    /** Plan goals; a plan without stored goals (seeded data) shows its template's goals. */
    public List<String> goalsOf(TreatmentPlan p) {
        if (p.getGoals() != null) {
            return support.readStrings(p.getGoals());
        }
        return p.getTemplateCode() == null ? List.of()
                : templates.findById(p.getTemplateCode()).map(t -> support.readStrings(t.getGoals())).orElse(List.of());
    }

    public List<PlanMaths.Intervention> interventionsOf(TreatmentPlan p) {
        if (p.getInterventions() != null) {
            return readInterventions(p.getInterventions());
        }
        return p.getTemplateCode() == null ? List.of() : templates.findById(p.getTemplateCode())
                .map(t -> PlanMaths.expandInterventions(support.readMaps(t.getInterventions()), p.getStartDate())).orElse(List.of());
    }

    private PlanFollowUp addFollowUp(TreatmentPlan p, LocalDate due, String type, String siteCode, boolean book) {
        Instant now = support.now();
        PlanFollowUp f = new PlanFollowUp();
        f.setId(UUID.randomUUID());
        f.setPlanId(p.getId());
        f.setCaseId(p.getCaseId());
        f.setEmployeeId(p.getEmployeeId());
        f.setDueDate(due);
        f.setType(type);
        f.setSiteCode(siteCode);
        f.setStatus("PLANNED");
        f.setBookRequested(book);
        f.setCreatedAt(now);
        f.setUpdatedAt(now);
        return followUps.saveAndFlush(f);
    }

    /** Follow-up of an active plan: kernel case task, and a clinic booking when requested and bookable. */
    private void schedule(TreatmentPlan p, PlanFollowUp f, String actor) {
        if (f.getTaskId() == null) {
            CaseTask t = work.systemTask(p.getCaseId(), CaseTask.Type.FOLLOW_UP, "Treatment follow-up (" + f.getType() + ")",
                    f.getDueDate().atStartOfDay().toInstant(ZoneOffset.UTC), p.getResponsibleClinician(), actor);
            f.setTaskId(t.getId());
        }
        String service = switch (f.getType()) {
            case "OH_REVIEW", "PHONE" -> "NURSE_REVIEW";
            case "TELEHEALTH" -> "TELEHEALTH_CONSULT";
            default -> null; // physiotherapy / specialist follow-ups are external (referral)
        };
        if (f.isBookRequested() && f.getAppointmentId() == null && service != null) {
            spis.book(actor, new AppointmentBooking.BookingRequest(f.getEmployeeId(), service, f.getSiteCode(), f.getDueDate(), null,
                    SOURCE_TYPE, f.getId().toString(), p.getCaseId())).ifPresent(b -> {
                        f.setAppointmentId(b.appointmentId());
                        if ("PLANNED".equals(f.getStatus())) {
                            f.setStatus("BOOKED");
                        }
                    });
        }
        f.setUpdatedAt(support.now());
    }

    private void activate(TreatmentPlan p, String actor) {
        p.setStatus("ACTIVE");
        p.setUpdatedAt(support.now());
        List<PlanFollowUp> list = followUps.findByPlanIdOrderByDueDateAsc(p.getId());
        for (PlanFollowUp f : list) {
            if (OPEN_FOLLOW_UP.contains(f.getStatus())) {
                schedule(p, f, actor);
            }
        }
        list.stream().filter(f -> OPEN_FOLLOW_UP.contains(f.getStatus()) && !f.getDueDate().isBefore(support.today())).findFirst()
                .ifPresent(f -> {
                    support.notifyEmployee(p.getEmployeeId(), "treatment.follow-up", Map.of("dueDate", f.getDueDate().toString()), "/#/treatment");
                    f.setRemindedAt(support.now());
                });
    }

    private boolean hasActive(UUID caseId, UUID except) {
        return plans.findByCaseIdOrderByCreatedAtDesc(caseId).stream()
                .anyMatch(x -> "ACTIVE".equals(x.getStatus()) && !x.getId().equals(except));
    }

    // ------------------------------------------------------------------ status changes

    @Transactional
    public PlanView status(CurrentUser user, UUID planId, String status, String note) {
        TreatmentPlan p = plans.findById(planId).orElseThrow(() -> ApiException.notFound("Treatment plan"));
        OhCase c = access.clinical(user, p.getCaseId(), AccessPolicy.Action.UPDATE, "TREATMENT_PLAN_STATUS");
        boolean ok = switch (p.getStatus()) {
            case "DRAFT" -> Set.of("ACTIVE", "CANCELLED").contains(status);
            case "ACTIVE" -> Set.of("COMPLETED", "CANCELLED").contains(status);
            default -> false;
        };
        if (!ok) {
            throw TreatmentSupport.unprocessable("invalid_transition", "Transition " + p.getStatus() + " -> " + status + " not allowed");
        }
        p.setStatusNote(TreatmentSupport.clinical("note", note, 2000));
        if ("ACTIVE".equals(status)) {
            if (hasActive(p.getCaseId(), p.getId())) {
                throw ApiException.conflict("active_plan_exists", "The case already has an active treatment plan");
            }
            activate(p, user.username());
        } else {
            p.setStatus(status);
            p.setUpdatedAt(support.now());
            if ("CANCELLED".equals(status)) {
                for (PlanFollowUp f : followUps.findByPlanIdOrderByDueDateAsc(p.getId())) {
                    if (OPEN_FOLLOW_UP.contains(f.getStatus())) {
                        f.setStatus("CANCELLED");
                        closeTask(f, false);
                    }
                }
            }
            alerts.findByPlanIdOrderByRaisedAtDesc(p.getId()).stream().filter(a -> OPEN_ALERT.contains(a.getStatus()))
                    .forEach(a -> a.setStatus("CLOSED"));
        }
        timeline.record(c.getId(), "TREATMENT_PLAN_STATUS", "Treatment plan " + status);
        audit.record("TREATMENT_PLAN_STATUS", "TREATMENT_PLAN", planId.toString(), AuditService.Outcome.SUCCESS, status);
        return view(p, c);
    }

    @Transactional
    public PlanView followUpStatus(CurrentUser user, UUID planId, UUID followUpId, String status, String note) {
        TreatmentPlan p = plans.findById(planId).orElseThrow(() -> ApiException.notFound("Treatment plan"));
        OhCase c = access.clinical(user, p.getCaseId(), AccessPolicy.Action.UPDATE, "TREATMENT_FOLLOW_UP_STATUS");
        PlanFollowUp f = followUps.findById(followUpId).filter(x -> x.getPlanId().equals(planId))
                .orElseThrow(() -> ApiException.notFound("Follow-up"));
        if (!OPEN_FOLLOW_UP.contains(f.getStatus()) && !"DECLINED".equals(f.getStatus())) {
            throw TreatmentSupport.unprocessable("invalid_transition", "The follow-up is already " + f.getStatus());
        }
        f.setStatus(status);
        f.setNote(TreatmentSupport.clinical("note", note, 2000));
        f.setUpdatedAt(support.now());
        closeTask(f, "DONE".equals(status) || "MISSED".equals(status));
        timeline.record(c.getId(), "TREATMENT_FOLLOW_UP", "Treatment follow-up " + status);
        audit.record("TREATMENT_FOLLOW_UP_STATUS", "FOLLOW_UP", followUpId.toString(), AuditService.Outcome.SUCCESS, status);
        return view(p, c);
    }

    private void closeTask(PlanFollowUp f, boolean done) {
        if (f.getTaskId() != null) {
            tasks.findById(f.getTaskId()).filter(t -> t.getStatus() == CaseTask.Status.OPEN).ifPresent(t -> {
                if (done) {
                    t.complete(support.now());
                } else {
                    t.cancel(support.now());
                }
            });
        }
    }

    /** Adds a follow-up to the case's active plan (e.g. after a telehealth consultation); no-op without an active plan. */
    @Transactional
    public Optional<UUID> addFollowUpToActivePlan(UUID caseId, LocalDate due, String type, String actor) {
        if (caseId == null || due == null) {
            return Optional.empty();
        }
        return plans.findByCaseIdOrderByCreatedAtDesc(caseId).stream().filter(x -> "ACTIVE".equals(x.getStatus())).findFirst().map(p -> {
            PlanFollowUp f = addFollowUp(p, due, type, null, false);
            schedule(p, f, actor);
            return f.getId();
        });
    }

    // ------------------------------------------------------------------ print

    @Transactional
    public DocumentService.DocumentView print(CurrentUser user, UUID planId) {
        TreatmentPlan p = plans.findById(planId).orElseThrow(() -> ApiException.notFound("Treatment plan"));
        OhCase c = access.clinical(user, p.getCaseId(), AccessPolicy.Action.EXPORT, "TREATMENT_PLAN_PRINT");
        List<String> goals = goalsOf(p);
        List<String> interventions = interventionsOf(p).stream()
                .map(i -> i.description() + (i.frequency() == null ? "" : " (" + i.frequency() + ")")
                        + (i.startDate() == null ? "" : " from " + i.startDate()) + (i.endDate() == null ? "" : " to " + i.endDate()))
                .toList();
        List<String> fus = followUps.findByPlanIdOrderByDueDateAsc(p.getId()).stream().filter(f -> !"CANCELLED".equals(f.getStatus()))
                .map(f -> f.getDueDate() + " " + f.getType().replace('_', ' ').toLowerCase(java.util.Locale.ROOT)).toList();
        LocalDate expected = expectedDate(p, c);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("employeeName", c.getEmployeeDisplayName() == null ? support.displayName(p.getEmployeeId()) : c.getEmployeeDisplayName());
        fields.put("caseRef", c.getCaseRef());
        fields.put("goals", trim(String.join("; ", goals), 4000));
        fields.put("interventions", trim(String.join("; ", interventions), 4000));
        fields.put("followUps", trim(String.join("; ", fus), 4000));
        fields.put("expectedRecovery", expected == null ? null : expected.toString());
        fields.put("clinician", trim(support.staffName(p.getResponsibleClinician()), 400));
        DocumentService.DocumentView d = documents.generate(user, "TREATMENT_PLAN", p.getEmployeeId(), p.getCaseId(), fields);
        d = documents.sign(user, d.id());
        documents.shareAsSystem(user.username(), d.id(), DocumentShare.Target.EMPLOYEE);
        p.setDocumentId(d.id());
        p.setUpdatedAt(support.now());
        timeline.record(c.getId(), "TREATMENT_PLAN_PRINTED", "Treatment plan document issued to the employee");
        audit.record("TREATMENT_PLAN_PRINT", "TREATMENT_PLAN", planId.toString(), AuditService.Outcome.SUCCESS, d.documentNumber());
        return d;
    }

    static String trim(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<PlanView> list(CurrentUser user, UUID caseId) {
        OhCase c = access.clinical(user, caseId, AccessPolicy.Action.READ, "TREATMENT_PLAN_LIST");
        List<PlanView> out = plans.findByCaseIdOrderByCreatedAtDesc(caseId).stream().filter(p -> !"SUPERSEDED".equals(p.getStatus()))
                .map(p -> view(p, c)).toList();
        audit.record("TREATMENT_PLAN_LIST", "CASE", caseId.toString(), AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    public PlanView view(TreatmentPlan p, OhCase c) {
        Guideline g = p.getGuidelineCode() == null ? null : guidelines.findById(p.getGuidelineCode())
                .map(x -> new Guideline(x.getIcdCode(), x.getTitle(), x.getExpectedDays())).orElse(null);
        PlanMaths.Variance v = variance(p, c);
        List<FollowUpView> fus = followUps.findByPlanIdOrderByDueDateAsc(p.getId()).stream()
                .map(f -> new FollowUpView(f.getId(), f.getDueDate(), f.getType(), f.getStatus(), f.getSiteCode(), f.getAppointmentId(),
                        f.getEmployeeResponse(), f.getResponseReason(), f.isBookRequested())).toList();
        List<HistoryEntry> history = plans.findBySeriesIdOrderByVersionAsc(p.getSeriesId()).stream().filter(x -> !x.getId().equals(p.getId()))
                .map(x -> new HistoryEntry(x.getId(), x.getVersion(), x.getStatus(), x.getCreatedAt(), x.getCreatedBy())).toList();
        return new PlanView(p.getId(), p.getCaseId(), c.getCaseRef(), p.getEmployeeId(), p.getStatus(), p.getVersion(), p.getTemplateCode(),
                goalsOf(p), interventionsOf(p), fus, p.getStartDate(), expectedDays(p),
                expectedDate(p, c), g, v == null ? null : new VarianceView(v.actualDays(), v.expectedDays(), v.pctOver(), v.alert()),
                p.getVarianceThresholdPct(), p.getResponsibleClinician(), p.getDocumentId(), p.getCreatedAt(), p.getCreatedBy(), history);
    }

    /** Expected days: plan override, else duration guideline, else template. */
    public Integer expectedDays(TreatmentPlan p) {
        if (p.getExpectedRecoveryDays() != null) {
            return p.getExpectedRecoveryDays();
        }
        if (p.getGuidelineCode() != null) {
            Optional<Integer> g = guidelines.findById(p.getGuidelineCode()).map(DurationGuideline::getExpectedDays);
            if (g.isPresent()) {
                return g.get();
            }
        }
        return p.getTemplateCode() == null ? null : templates.findById(p.getTemplateCode()).map(PlanTemplate::getExpectedDays).orElse(null);
    }

    /** Recovery is measured from the date of injury / onset when known, else from the plan start. */
    static LocalDate recoveryStart(TreatmentPlan p, OhCase c) {
        return c != null && c.getOccurredOn() != null && !c.getOccurredOn().isAfter(p.getStartDate()) ? c.getOccurredOn() : p.getStartDate();
    }

    LocalDate expectedDate(TreatmentPlan p, OhCase c) {
        return PlanMaths.expectedRecoveryDate(recoveryStart(p, c), expectedDays(p));
    }

    public PlanMaths.Variance variance(TreatmentPlan p, OhCase c) {
        LocalDate end = "COMPLETED".equals(p.getStatus()) ? LocalDate.ofInstant(p.getUpdatedAt(), ZoneOffset.UTC) : support.today();
        return PlanMaths.variance(recoveryStart(p, c), end, expectedDays(p), threshold(p));
    }

    int threshold(TreatmentPlan p) {
        return p.getVarianceThresholdPct() != null ? p.getVarianceThresholdPct() : support.configInt("treatment.variance.threshold-pct", 20);
    }

    // ------------------------------------------------------------------ variance alerts

    @Transactional(readOnly = true)
    public List<AlertView> alerts(CurrentUser user, String status) {
        PageRequest page = PageRequest.of(0, 500);
        List<VarianceAlert> list = status == null ? alerts.findAllByOrderByRaisedAtDesc(page) : alerts.findByStatusOrderByRaisedAtDesc(status, page);
        List<AlertView> out = new ArrayList<>();
        for (VarianceAlert a : list) {
            Optional<OhCase> c = access.find(a.getCaseId());
            if (c.isPresent() && access.canSeeClinical(user, c.get())) {
                out.add(view(a, c.get()));
            }
        }
        audit.record("TREATMENT_VARIANCE_LIST", "VARIANCE_ALERT", null, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    AlertView view(VarianceAlert a, OhCase c) {
        return new AlertView(a.getId(), a.getCaseId(), c.getCaseRef(), a.getEmployeeId(), support.displayName(a.getEmployeeId()), a.getPlanId(),
                a.getExpectedDays(), a.getActualDays(), a.getPctOver(), a.getStatus(), a.getRaisedAt(), a.getActedBy(), a.getActedAt());
    }

    @Transactional
    public AlertView actOnAlert(CurrentUser user, UUID id, boolean escalate, String note) {
        VarianceAlert a = alerts.findById(id).orElseThrow(() -> ApiException.notFound("Variance alert"));
        OhCase c = access.clinical(user, a.getCaseId(), AccessPolicy.Action.UPDATE, "TREATMENT_VARIANCE_ACT");
        if (!OPEN_ALERT.contains(a.getStatus()) || "ESCALATED".equals(a.getStatus()) || (!escalate && "ACKNOWLEDGED".equals(a.getStatus()))) {
            throw TreatmentSupport.unprocessable("invalid_transition", "The variance alert is already " + a.getStatus());
        }
        a.setStatus(escalate ? "ESCALATED" : "ACKNOWLEDGED");
        a.setActedBy(user.username());
        a.setActedAt(support.now());
        a.setNote(TreatmentSupport.clinical("note", note, 2000));
        if (escalate) {
            Map<String, String> params = Map.of("caseRef", c.getCaseRef(), "level", "escalated, " + a.getPctOver() + "% over expected");
            support.notifyRole(Roles.NURSING_HEAD, "treatment.variance", params, "/#/treatment/variance");
            support.notifyRole(Roles.CMO, "treatment.variance", params, "/#/treatment/variance");
        }
        timeline.record(c.getId(), "TREATMENT_VARIANCE_" + a.getStatus(), "Treatment variance alert " + a.getStatus().toLowerCase(java.util.Locale.ROOT));
        audit.record("TREATMENT_VARIANCE_" + a.getStatus(), "VARIANCE_ALERT", id.toString(), AuditService.Outcome.SUCCESS, null);
        return view(a, c);
    }

    /** Nightly {@code treatment-variance}: raises one alert per active plan whose recovery overran the threshold. */
    @Transactional
    public int runVariance() {
        int raised = 0;
        for (TreatmentPlan p : plans.findByStatus("ACTIVE")) {
            OhCase c = access.find(p.getCaseId()).orElse(null);
            PlanMaths.Variance v = variance(p, c);
            if (c == null || v == null || !v.alert() || alerts.existsByPlanIdAndStatusIn(p.getId(), OPEN_ALERT)) {
                continue;
            }
            raised++;
            raiseAlert(p, c, v);
        }
        return raised;
    }

    private void raiseAlert(TreatmentPlan p, OhCase c, PlanMaths.Variance v) {
        VarianceAlert a = new VarianceAlert();
        a.setId(UUID.randomUUID());
        a.setPlanId(p.getId());
        a.setCaseId(p.getCaseId());
        a.setEmployeeId(p.getEmployeeId());
        a.setExpectedDays(v.expectedDays());
        a.setActualDays(v.actualDays());
        a.setPctOver(v.pctOver());
        a.setStatus("OPEN");
        a.setRaisedAt(support.now());
        alerts.save(a);
        support.notifyStaff(p.getResponsibleClinician(), "treatment.variance",
                Map.of("caseRef", c.getCaseRef(), "level", v.pctOver() + "% over expected"), "/#/cases/" + c.getId() + "/treatment");
        timeline.record(c.getId(), "TREATMENT_VARIANCE", "Expected recovery duration exceeded by " + v.pctOver() + "%");
        bus.publish(new E04Events.TreatmentVariance(p.getId().toString(), c.getId().toString()));
        audit.recordAs("system", "MODULE", "TREATMENT_VARIANCE_RAISED", "TREATMENT_PLAN", p.getId().toString(), AuditService.Outcome.SUCCESS,
                "pctOver=" + v.pctOver());
    }

    /** Daily employee reminders for follow-ups due within the reminder window. */
    @Transactional
    public int remindFollowUps() {
        LocalDate today = support.today();
        int n = 0;
        int days = support.configInt("treatment.follow-up.reminder-days", 2);
        for (PlanFollowUp f : followUps.findByStatusInAndDueDateBetween(OPEN_FOLLOW_UP, today, today.plusDays(days))) {
            if (f.getRemindedAt() != null) {
                continue;
            }
            TreatmentPlan p = plans.findById(f.getPlanId()).orElse(null);
            if (p == null || !"ACTIVE".equals(p.getStatus())) {
                continue;
            }
            support.notifyEmployee(f.getEmployeeId(), "treatment.follow-up", Map.of("dueDate", f.getDueDate().toString()), "/#/treatment");
            f.setRemindedAt(support.now());
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ employee

    @Transactional(readOnly = true)
    public List<MyPlan> mine(CurrentUser user) {
        if (user.employeeId() == null) {
            return List.of();
        }
        List<MyPlan> out = new ArrayList<>();
        Map<String, ServiceDefinition> svc = new LinkedHashMap<>();
        services.findAll().forEach(s -> svc.put(s.getCode(), s));
        for (TreatmentPlan p : plans.findByEmployeeIdOrderByCreatedAtDesc(user.employeeId())) {
            if (!Set.of("ACTIVE", "COMPLETED").contains(p.getStatus())) {
                continue;
            }
            OhCase c = access.find(p.getCaseId()).orElse(null);
            List<MyIntervention> interventions = interventionsOf(p).stream()
                    .map(i -> new MyIntervention(i.type(), i.description(), i.frequency(), i.startDate(), i.endDate(),
                            i.providerCode() == null ? null : support.partnerName(i.providerCode()))).toList();
            List<MyFollowUp> fus = new ArrayList<>();
            for (PlanFollowUp f : followUps.findByPlanIdOrderByDueDateAsc(p.getId())) {
                Instant at = f.getAppointmentId() == null ? null : spis.bookingsFor(SOURCE_TYPE, f.getId().toString()).stream()
                        .filter(b -> b.appointmentId().equals(f.getAppointmentId())).map(AppointmentBooking.Booking::startsAt).findFirst().orElse(null);
                fus.add(new MyFollowUp(f.getId(), f.getDueDate(), f.getType(), f.getStatus(), f.getAppointmentId(), at, f.getSiteCode(),
                        f.getEmployeeResponse()));
            }
            List<ExternalAppointment> ext = new ArrayList<>();
            for (Referral r : referrals.findByCaseIdOrderByCreatedAtDesc(p.getCaseId())) {
                if (r.getAppointmentAt() != null && Set.of("SCHEDULED", "SEEN", "REPORT_RECEIVED").contains(r.getStatus())) {
                    ServiceDefinition s = svc.get(r.getServiceCode());
                    ext.add(new ExternalAppointment(r.getRef(), s == null ? r.getServiceCode() : s.getName(), r.getProviderCode(),
                            support.partnerName(r.getProviderCode()), r.getAppointmentAt(), r.getAppointmentLocation(),
                            r.getAppointmentInstructions(), r.getStatus()));
                }
            }
            out.add(new MyPlan(p.getId(), p.getStatus(), goalsOf(p), interventions, fus, expectedDate(p, c), ext));
        }
        audit.record("TREATMENT_PLAN_SELF_READ", "EMPLOYEE", user.employeeId(), AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    @Transactional
    public MyFollowUp respond(CurrentUser user, UUID planId, UUID followUpId, String response, String reason) {
        TreatmentPlan p = plans.findById(planId).filter(x -> user.employeeId() != null && user.employeeId().equals(x.getEmployeeId()))
                .orElseThrow(() -> ApiException.notFound("Treatment plan"));
        PlanFollowUp f = followUps.findById(followUpId).filter(x -> x.getPlanId().equals(planId))
                .orElseThrow(() -> ApiException.notFound("Follow-up"));
        if (!"ACTIVE".equals(p.getStatus()) || !Set.of("PLANNED", "BOOKED").contains(f.getStatus()) || f.getEmployeeResponse() != null) {
            throw TreatmentSupport.unprocessable("response_not_allowed", "This follow-up can no longer be confirmed or declined");
        }
        f.setEmployeeResponse(response);
        f.setResponseReason(TreatmentSupport.functional("reason", reason, 500));
        f.setStatus("CONFIRM".equals(response) ? "CONFIRMED" : "DECLINED");
        f.setUpdatedAt(support.now());
        timeline.record(p.getCaseId(), "TREATMENT_FOLLOW_UP_" + f.getStatus(), "Employee " + f.getStatus().toLowerCase(java.util.Locale.ROOT)
                + " a treatment follow-up");
        if ("DECLINED".equals(f.getStatus())) {
            support.notifyStaff(p.getResponsibleClinician(), "action.required", Map.of("dueDate", f.getDueDate().toString()),
                    "/#/cases/" + p.getCaseId() + "/treatment");
        }
        audit.record("TREATMENT_FOLLOW_UP_RESPONSE", "FOLLOW_UP", followUpId.toString(), AuditService.Outcome.SUCCESS, response);
        return new MyFollowUp(f.getId(), f.getDueDate(), f.getType(), f.getStatus(), f.getAppointmentId(), null, f.getSiteCode(),
                f.getEmployeeResponse());
    }

    // ------------------------------------------------------------------ scheduling callbacks

    /** E14 callback for a follow-up booking: NO_SHOW → MISSED, ATTENDED → DONE, CANCELLED → back to PLANNED. */
    @Transactional
    public void appointmentChanged(UUID appointmentId, String sourceRef, AppointmentStatusListener.Status status) {
        if (sourceRef == null || status == null) {
            return;
        }
        UUID id;
        try {
            id = UUID.fromString(sourceRef);
        } catch (IllegalArgumentException e) {
            return;
        }
        followUps.findById(id).ifPresent(f -> {
            switch (status) {
                case BOOKED -> {
                    f.setAppointmentId(appointmentId);
                    if ("PLANNED".equals(f.getStatus())) {
                        f.setStatus("BOOKED");
                    }
                }
                case NO_SHOW -> {
                    f.setStatus("MISSED");
                    closeTask(f, true);
                }
                case ATTENDED -> {
                    f.setStatus("DONE");
                    closeTask(f, true);
                }
                case CANCELLED -> {
                    if (OPEN_FOLLOW_UP.contains(f.getStatus())) {
                        f.setStatus("PLANNED");
                        f.setAppointmentId(null);
                    }
                }
                default -> {
                    return;
                }
            }
            f.setUpdatedAt(support.now());
            timeline.record(f.getCaseId(), "TREATMENT_FOLLOW_UP", "Follow-up appointment " + status.name().toLowerCase(java.util.Locale.ROOT));
        });
    }

    public Collection<String> openFollowUpStatuses() {
        return OPEN_FOLLOW_UP;
    }
}
