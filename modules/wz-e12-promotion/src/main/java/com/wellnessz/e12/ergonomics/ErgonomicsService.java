package com.wellnessz.e12.ergonomics;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.common.model.Roles;
import com.wellnessz.documents.DocumentService;
import com.wellnessz.e12.model.E12CampaignRepository;
import com.wellnessz.e12.model.E12ErgonomicActionRepository;
import com.wellnessz.e12.model.E12ErgonomicAssessmentRepository;
import com.wellnessz.e12.model.E12ErgonomicChecklistRepository;
import com.wellnessz.e12.model.E12ErgonomicPhotoRepository;
import com.wellnessz.e12.model.ErgonomicAction;
import com.wellnessz.e12.model.ErgonomicAssessment;
import com.wellnessz.e12.model.ErgonomicChecklist;
import com.wellnessz.e12.model.ErgonomicPhoto;
import com.wellnessz.e12.rules.ErgonomicScoring;
import com.wellnessz.e12.rules.Suppression;
import com.wellnessz.e12.support.PromotionSupport;
import com.wellnessz.masterdata.fitness.JobDemandProfile;
import com.wellnessz.masterdata.fitness.JobDemandProfileRepository;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

import tools.jackson.core.type.TypeReference;

/**
 * Ergonomic assessment module (E12-F03): checklist templates (DSE workstation, manual handling, task) scored 0–3 per item into a
 * risk score and RAG, the masterdata job-demand profile attached, workstation / task photos stored as non-clinical documents,
 * and corrective actions with owners and due dates tracked to closure (KPI: closed within 60 days ≥ 80 %).
 */
@Service
public class ErgonomicsService {

    public static final String TYPES = "DSE_WORKSTATION|MANUAL_HANDLING|TASK";

    private final E12ErgonomicChecklistRepository checklists;
    private final E12ErgonomicAssessmentRepository assessments;
    private final E12ErgonomicPhotoRepository photos;
    private final E12ErgonomicActionRepository actions;
    private final JobDemandProfileRepository jobDemands;
    private final DocumentService documents;
    private final PromotionSupport support;
    private final AuditService audit;
    private final E12CampaignRepository refs;

    public ErgonomicsService(E12ErgonomicChecklistRepository checklists, E12ErgonomicAssessmentRepository assessments, E12ErgonomicPhotoRepository photos,
                             E12ErgonomicActionRepository actions, JobDemandProfileRepository jobDemands, DocumentService documents,
                             PromotionSupport support, AuditService audit, E12CampaignRepository refs) {
        this.refs = refs;
        this.checklists = checklists;
        this.assessments = assessments;
        this.photos = photos;
        this.actions = actions;
        this.jobDemands = jobDemands;
        this.documents = documents;
        this.support = support;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ DTOs

    public record ChecklistView(String code, String title, List<ErgonomicScoring.Item> items, int amberFrom, int redFrom) {
    }

    public record AssessmentCommand(@NotBlank @Pattern(regexp = TYPES) String type,
                                    @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9_\\-]{0,63}") String unitCode,
                                    @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9_\\-]{0,63}") String jobCode,
                                    @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String employeeId,
                                    @NotBlank @Size(max = 200) String location,
                                    @NotEmpty @Size(max = 40) Map<@Pattern(regexp = "[A-Z][A-Z0-9_]{0,39}") String, @NotNull @Min(0) @Max(3) Integer> checklist,
                                    @Size(max = 4000) String findings) {
    }

    public record PhotoView(UUID id, UUID documentId, String uploadedBy, Instant uploadedAt) {
    }

    public record ActionView(UUID id, UUID assessmentId, String assessmentRef, String ref, String title, String owner, LocalDate dueDate,
                             String status, String unitCode, boolean overdue, Instant createdAt, Instant closedAt, String updatedBy) {
    }

    public record AssessmentView(UUID id, String ref, String type, String unitCode, String jobCode, String employeeId, String displayName,
                                 String location, Map<String, Integer> checklist, String findings, int riskScore, String rag,
                                 Map<String, Object> jobDemandProfile, String assessedBy, Instant assessedAt, List<PhotoView> photos,
                                 List<ActionView> actions) {
    }

    public record ActionCommand(@NotBlank @Size(max = 300) String title, @NotBlank @Pattern(regexp = "[A-Za-z0-9 ._'@\\-]{1,200}") String owner,
                                @NotNull LocalDate dueDate, @Pattern(regexp = "OPEN|DONE") String status) {
    }

    public record ActionUpdate(@Size(max = 300) String title, @Pattern(regexp = "[A-Za-z0-9 ._'@\\-]{1,200}") String owner, LocalDate dueDate,
                               @Pattern(regexp = "OPEN|DONE") String status) {
    }

    public record Kpi(long actions, long open, long overdue, long eligible, long closedWithinTarget, BigDecimal pct, int targetDays,
                      int targetPct) {
    }

    public record Workspace(List<String> units, List<ActionView> actions, Kpi kpi) {
    }

    // ------------------------------------------------------------------ checklists

    @Transactional(readOnly = true)
    public List<ChecklistView> checklists() {
        return checklists.findAllByOrderByCodeAsc().stream().map(this::view).toList();
    }

    ChecklistView view(ErgonomicChecklist c) {
        return new ChecklistView(c.getCode(), c.getTitle(), items(c), c.getAmberFrom(), c.getRedFrom());
    }

    List<ErgonomicScoring.Item> items(ErgonomicChecklist c) {
        return support.read(c.getItems(), new TypeReference<List<ErgonomicScoring.Item>>() {
        });
    }

    // ------------------------------------------------------------------ assessments

    @Transactional
    public AssessmentView create(CurrentUser user, AssessmentCommand cmd) {
        if (!support.unitExists(cmd.unitCode())) {
            throw PromotionSupport.unprocessable("unknown_unit", "Unknown org unit");
        }
        if (cmd.employeeId() != null) {
            support.requireEmployee(cmd.employeeId());
        }
        ErgonomicChecklist template = checklists.findById(cmd.type())
                .orElseThrow(() -> PromotionSupport.unprocessable("unknown_checklist", "No checklist template for this assessment type"));
        ErgonomicScoring.Score score = ErgonomicScoring.score(items(template), cmd.checklist(), template.getAmberFrom(), template.getRedFrom());
        Instant now = support.now();
        ErgonomicAssessment a = new ErgonomicAssessment();
        a.setId(UUID.randomUUID());
        a.setAssessmentRef(support.nextRef("ERG", refs.nextRef()));
        a.setAssessmentType(cmd.type());
        a.setUnitCode(cmd.unitCode());
        a.setJobCode(cmd.jobCode());
        a.setEmployeeId(cmd.employeeId());
        a.setLocation(PromotionSupport.functional("location", cmd.location(), 200));
        a.setChecklist(support.write(new java.util.TreeMap<>(cmd.checklist())));
        a.setFindings(PromotionSupport.functional("findings", cmd.findings(), 4000));
        a.setRiskScore(score.riskScore());
        a.setRag(score.rag());
        a.setJobDemand(support.write(jobDemand(cmd.jobCode())));
        a.setAssessedBy(user.username());
        a.setAssessedAt(now);
        a.setUpdatedAt(now);
        assessments.save(a);
        audit.record("ERGONOMIC_ASSESSMENT_CREATE", "ERGONOMIC_ASSESSMENT", a.getId().toString(), AuditService.Outcome.SUCCESS,
                cmd.type() + " rag=" + score.rag());
        return view(a);
    }

    Map<String, Object> jobDemand(String jobCode) {
        JobDemandProfile p = jobDemands.findById(jobCode).orElse(null);
        if (p == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jobCode", p.getJobCode());
        m.put("title", p.getTitle());
        m.put("unit", p.getUnit());
        m.put("demands", support.readMap(p.getDemands()));
        if (p.getSitting() != null) {
            m.put("sitting", p.getSitting());
        }
        return m;
    }

    @Transactional(readOnly = true)
    public List<AssessmentView> list(CurrentUser user, String unitCode, String rag) {
        List<ErgonomicAssessment> all = unitCode == null ? assessments.findAllByOrderByAssessedAtDesc()
                : assessments.findByUnitCodeInOrderByAssessedAtDesc(List.of(unitCode));
        return all.stream().filter(a -> rag == null || rag.equals(a.getRag())).limit(1000).map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public AssessmentView get(CurrentUser user, UUID id) {
        AssessmentView v = view(require(id));
        audit.record("ERGONOMIC_ASSESSMENT_READ", "ERGONOMIC_ASSESSMENT", id.toString(), AuditService.Outcome.SUCCESS, null);
        return v;
    }

    ErgonomicAssessment require(UUID id) {
        return assessments.findById(id).orElseThrow(() -> ApiException.notFound("Ergonomic assessment"));
    }

    AssessmentView view(ErgonomicAssessment a) {
        Map<String, Integer> checklist = support.read(a.getChecklist(), new TypeReference<LinkedHashMap<String, Integer>>() {
        });
        Map<String, Object> demand = a.getJobDemand() == null ? null : support.readMap(a.getJobDemand());
        List<PhotoView> ph = photos.findByAssessmentIdOrderByUploadedAtAsc(a.getId()).stream()
                .map(p -> new PhotoView(p.getId(), p.getDocumentId(), p.getUploadedBy(), p.getUploadedAt())).toList();
        List<ActionView> ac = actions.findByAssessmentIdOrderByDueDateAsc(a.getId()).stream().map(x -> view(x, a.getAssessmentRef())).toList();
        return new AssessmentView(a.getId(), a.getAssessmentRef(), a.getAssessmentType(), a.getUnitCode(), a.getJobCode(), a.getEmployeeId(),
                a.getEmployeeId() == null ? null : support.displayName(a.getEmployeeId()), a.getLocation(), checklist, a.getFindings(),
                a.getRiskScore(), a.getRag(), demand, a.getAssessedBy(), a.getAssessedAt(), ph, ac);
    }

    ActionView view(ErgonomicAction x, String assessmentRef) {
        boolean overdue = "OPEN".equals(x.getStatus()) && x.getDueDate().isBefore(support.today());
        return new ActionView(x.getId(), x.getAssessmentId(), assessmentRef, x.getActionRef(), x.getTitle(), x.getOwner(), x.getDueDate(),
                x.getStatus(), x.getUnitCode(), overdue, x.getCreatedAt(), x.getClosedAt(), x.getUpdatedBy());
    }

    // ------------------------------------------------------------------ photos

    @Transactional
    public PhotoView photo(CurrentUser user, UUID id, byte[] bytes, String title) {
        ErgonomicAssessment a = require(id);
        if (photos.findByAssessmentIdOrderByUploadedAtAsc(id).size() >= 20) {
            throw PromotionSupport.unprocessable("too_many_photos", "At most 20 photos per assessment");
        }
        String subject = a.getEmployeeId() != null ? a.getEmployeeId() : a.getAssessmentRef();
        DocumentService.Stored stored = documents.storeUpload(user.username(), subject, null, "ERGONOMIC_PHOTO",
                PromotionSupport.functional("title", title == null ? a.getAssessmentRef() + " photo" : title, 200), bytes,
                DataClassification.CONFIDENTIAL);
        String type = stored.document().contentType();
        if (type == null || !type.startsWith("image/")) {
            throw ApiException.badRequest("invalid_file_type", "Only JPEG or PNG images are accepted");
        }
        ErgonomicPhoto p = new ErgonomicPhoto();
        p.setId(UUID.randomUUID());
        p.setAssessmentId(id);
        p.setDocumentId(stored.document().id());
        p.setUploadedBy(user.username());
        p.setUploadedAt(support.now());
        photos.save(p);
        audit.record("ERGONOMIC_PHOTO_UPLOAD", "ERGONOMIC_ASSESSMENT", id.toString(), AuditService.Outcome.SUCCESS, "document=" + p.getDocumentId());
        return new PhotoView(p.getId(), p.getDocumentId(), p.getUploadedBy(), p.getUploadedAt());
    }

    // ------------------------------------------------------------------ actions

    @Transactional
    public ActionView addAction(CurrentUser user, UUID assessmentId, ActionCommand cmd) {
        ErgonomicAssessment a = require(assessmentId);
        if (cmd.dueDate().isBefore(support.today())) {
            throw PromotionSupport.unprocessable("due_date_past", "The due date must not be in the past");
        }
        Instant now = support.now();
        long n = actions.findByAssessmentIdOrderByDueDateAsc(assessmentId).size() + 1L;
        ErgonomicAction x = new ErgonomicAction();
        x.setId(UUID.randomUUID());
        x.setAssessmentId(assessmentId);
        x.setActionRef(a.getAssessmentRef() + "-A" + n);
        x.setTitle(PromotionSupport.functional("title", cmd.title(), 300));
        x.setOwner(PromotionSupport.functional("owner", cmd.owner(), 200));
        x.setDueDate(cmd.dueDate());
        x.setUnitCode(a.getUnitCode());
        x.setCreatedBy(user.username());
        x.setCreatedAt(now);
        x.setUpdatedBy(user.username());
        x.setUpdatedAt(now);
        setStatus(x, cmd.status() == null ? "OPEN" : cmd.status(), now);
        actions.save(x);
        audit.record("ERGONOMIC_ACTION_CREATE", "ERGONOMIC_ACTION", x.getId().toString(), AuditService.Outcome.SUCCESS, x.getActionRef());
        return view(x, a.getAssessmentRef());
    }

    @Transactional
    public ActionView updateAction(CurrentUser user, UUID assessmentId, UUID actionId, ActionUpdate cmd) {
        ErgonomicAssessment a = require(assessmentId);
        ErgonomicAction x = actions.findById(actionId).filter(v -> v.getAssessmentId().equals(assessmentId))
                .orElseThrow(() -> ApiException.notFound("Ergonomic action"));
        boolean specialist = user.hasAnyRole(Roles.ERGONOMIST, Roles.HSE_OFFICER);
        if (!specialist) {
            if (!support.unitsInScope(user).contains(x.getUnitCode())) {
                audit.record("ERGONOMIC_ACTION_UPDATE", "ERGONOMIC_ACTION", actionId.toString(), AuditService.Outcome.DENIED, "outside unit");
                throw ApiException.notFound("Ergonomic action");
            }
            if (cmd.title() != null || cmd.owner() != null || cmd.dueDate() != null) {
                throw ApiException.forbidden("Managers may only update the status of an action");
            }
        }
        if (cmd.title() != null) {
            x.setTitle(PromotionSupport.functional("title", cmd.title(), 300));
        }
        if (cmd.owner() != null) {
            x.setOwner(PromotionSupport.functional("owner", cmd.owner(), 200));
        }
        if (cmd.dueDate() != null) {
            if (cmd.dueDate().isBefore(support.today()) && !cmd.dueDate().equals(x.getDueDate())) {
                throw PromotionSupport.unprocessable("due_date_past", "The due date must not be in the past");
            }
            x.setDueDate(cmd.dueDate());
        }
        Instant now = support.now();
        if (cmd.status() != null) {
            setStatus(x, cmd.status(), now);
        }
        x.setUpdatedBy(user.username());
        x.setUpdatedAt(now);
        audit.record("ERGONOMIC_ACTION_UPDATE", "ERGONOMIC_ACTION", actionId.toString(), AuditService.Outcome.SUCCESS, "status=" + x.getStatus());
        return view(x, a.getAssessmentRef());
    }

    static void setStatus(ErgonomicAction x, String status, Instant now) {
        if ("DONE".equals(status) && !"DONE".equals(x.getStatus())) {
            x.setClosedAt(now);
        } else if ("OPEN".equals(status)) {
            x.setClosedAt(null);
        }
        x.setStatus(status);
    }

    // ------------------------------------------------------------------ KPI, manager workspace, reminders

    /** Closed-within-target KPI over actions whose target window has elapsed or that are closed. */
    public static Kpi kpi(Collection<ErgonomicAction> list, LocalDate today, int targetDays, int targetPct) {
        long open = 0;
        long overdue = 0;
        long eligible = 0;
        long met = 0;
        for (ErgonomicAction x : list) {
            boolean done = "DONE".equals(x.getStatus()) && x.getClosedAt() != null;
            if (!done) {
                open++;
                if (x.getDueDate().isBefore(today)) {
                    overdue++;
                }
            }
            boolean windowElapsed = x.getCreatedAt() != null
                    && !LocalDate.ofInstant(x.getCreatedAt(), java.time.ZoneOffset.UTC).plusDays(targetDays).isAfter(today);
            if (done || windowElapsed) {
                eligible++;
                if (done && Duration.between(x.getCreatedAt(), x.getClosedAt()).toDays() <= targetDays) {
                    met++;
                }
            }
        }
        return new Kpi(list.size(), open, overdue, eligible, met, Suppression.pct(met, eligible), targetDays, targetPct);
    }

    @Transactional(readOnly = true)
    public Kpi kpi(String unitCode) {
        List<ErgonomicAction> list = unitCode == null ? actions.findAllByOrderByDueDateAsc() : actions.findByUnitCodeInOrderByDueDateAsc(List.of(unitCode));
        return kpi(list, support.today(), support.configInt("promotion.ergonomics.closure-target-days", 60), 80);
    }

    @Transactional(readOnly = true)
    public Workspace workspace(CurrentUser user) {
        Set<String> units = support.unitsInScope(user);
        List<ErgonomicAction> list = units.isEmpty() ? List.of() : actions.findByUnitCodeInOrderByDueDateAsc(units);
        Map<UUID, String> refByAssessment = new LinkedHashMap<>();
        List<ActionView> out = new ArrayList<>();
        for (ErgonomicAction x : list) {
            String ref = refByAssessment.computeIfAbsent(x.getAssessmentId(), id -> assessments.findById(id).map(ErgonomicAssessment::getAssessmentRef).orElse(null));
            out.add(view(x, ref));
        }
        audit.record("ERGONOMIC_WORKSPACE", "ERGONOMIC_ACTION", null, AuditService.Outcome.SUCCESS, "units=" + units + " rows=" + out.size());
        return new Workspace(List.copyOf(units), out,
                kpi(list, support.today(), support.configInt("promotion.ergonomics.closure-target-days", 60), 80));
    }

    /** {@code ergonomic-actions} job: content-free reminder to the owner (and the assessor) of actions due soon or overdue. */
    @Transactional
    public int remind(LocalDate today) {
        int days = Math.max(1, support.configInt("promotion.ergonomics.reminder-days", 7));
        int sent = 0;
        for (ErgonomicAction x : actions.findByStatusAndDueDateLessThanEqualOrderByDueDateAsc("OPEN", today.plusDays(days))) {
            if (x.getLastRemindedOn() != null && x.getLastRemindedOn().isAfter(today.minusDays(days))) {
                continue;
            }
            Map<String, String> params = Map.of("reference", x.getActionRef(), "unit", x.getUnitCode(), "dueDate", x.getDueDate().toString());
            support.notifyOwner(x.getOwner(), "ergonomic.action-due", params, true);
            assessments.findById(x.getAssessmentId()).map(ErgonomicAssessment::getAssessedBy).filter(s -> !s.equals(x.getOwner()))
                    .ifPresent(s -> support.notifyOwner(s, "ergonomic.action-due", params, false));
            x.setLastRemindedOn(today);
            sent++;
        }
        return sent;
    }
}
