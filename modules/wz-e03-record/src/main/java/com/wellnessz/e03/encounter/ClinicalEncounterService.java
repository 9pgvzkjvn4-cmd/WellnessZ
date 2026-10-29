package com.wellnessz.e03.encounter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OhCaseRepository;
import com.wellnessz.casekernel.support.CaseAccess;
import com.wellnessz.casekernel.support.CaseTimeline;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.common.model.Roles;
import com.wellnessz.documents.DocumentService;
import com.wellnessz.e03.E03Events;
import com.wellnessz.e03.cds.CdsAlert;
import com.wellnessz.e03.chart.JobHistoryService;
import com.wellnessz.e03.model.ClinicalEncounter;
import com.wellnessz.e03.model.EncounterAddendum;
import com.wellnessz.e03.model.EncounterAddendumRepository;
import com.wellnessz.e03.model.EncounterDiagnosis;
import com.wellnessz.e03.model.EncounterDiagnosisRepository;
import com.wellnessz.e03.model.EncounterObservation;
import com.wellnessz.e03.model.EncounterObservationRepository;
import com.wellnessz.e03.model.EncounterPhoto;
import com.wellnessz.e03.model.EncounterPhotoRepository;
import com.wellnessz.e03.model.ClinicalEncounterRepository;
import com.wellnessz.e03.model.ResultRepository;
import com.wellnessz.e03.support.CodeRef;
import com.wellnessz.e03.support.JsonSupport;
import com.wellnessz.e03.support.RecordGuard;
import com.wellnessz.e03.support.RecordProblem;
import com.wellnessz.e03.terminology.TerminologyCatalog;
import com.wellnessz.integration.outbox.EventBus;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * ClinicalEncounter documentation (E03-F02): template-driven DRAFT → SIGNED lifecycle with SHA-256 signature, signed addenda,
 * entered-in-error, body map stored as coded observations, consented photos, unsigned-note reminders. MENTAL_HEALTH
 * encounters are RESTRICTED_CLINICAL. A primary diagnosis coded on an encounter linked to a case becomes the case's
 * primary diagnosis (closure rule E03-F03).
 */
@Service
public class ClinicalEncounterService {

    public static final Set<String> REGIONS = Set.of("HEAD", "FACE", "EYE", "EAR", "NECK", "SHOULDER", "UPPER_ARM", "ELBOW", "FOREARM",
            "WRIST", "HAND", "FINGER", "CHEST", "ABDOMEN", "UPPER_BACK", "LOWER_BACK", "PELVIS", "HIP", "THIGH", "KNEE", "LOWER_LEG",
            "ANKLE", "FOOT", "TOE", "SKIN_GENERAL");
    static final String REGION_PATTERN = "HEAD|FACE|EYE|EAR|NECK|SHOULDER|UPPER_ARM|ELBOW|FOREARM|WRIST|HAND|FINGER|CHEST|ABDOMEN|"
            + "UPPER_BACK|LOWER_BACK|PELVIS|HIP|THIGH|KNEE|LOWER_LEG|ANKLE|FOOT|TOE|SKIN_GENERAL";
    static final String MECHANISM_PATTERN = "FALL|STRUCK_BY|CAUGHT_BETWEEN|LIFTING|PUSH_PULL|SLIP_TRIP|CUT|BURN_HEAT|BURN_CHEMICAL|"
            + "REPETITIVE|VEHICLE|OTHER";
    public static final List<Mechanism> MECHANISMS = List.of(new Mechanism("FALL", "Fall from height"),
            new Mechanism("STRUCK_BY", "Struck by object / equipment"), new Mechanism("CAUGHT_BETWEEN", "Caught in or between"),
            new Mechanism("LIFTING", "Lifting / carrying"), new Mechanism("PUSH_PULL", "Pushing / pulling"),
            new Mechanism("SLIP_TRIP", "Slip or trip (same level)"), new Mechanism("CUT", "Cut / puncture by sharp object"),
            new Mechanism("BURN_HEAT", "Burn – heat / hot surface or liquid"), new Mechanism("BURN_CHEMICAL", "Burn – chemical"),
            new Mechanism("REPETITIVE", "Repetitive movement / overuse"), new Mechanism("VEHICLE", "Vehicle / GSE collision"),
            new Mechanism("OTHER", "Other"));
    static final String TYPES = "INJURY|ILLNESS|FITNESS_ASSESSMENT|SURVEILLANCE_EXAM|MENTAL_HEALTH|TRAVEL|CREW_AEROMEDICAL|FOOD_HANDLER";
    static final int MAX_ANSWER_LENGTH = 4000;

    private final ClinicalEncounterRepository encounters;
    private final EncounterDiagnosisRepository diagnoses;
    private final EncounterObservationRepository observations;
    private final EncounterAddendumRepository addenda;
    private final EncounterPhotoRepository photos;
    private final ResultRepository results;
    private final EncounterTemplates templates;
    private final EncounterCds cds;
    private final JobHistoryService jobs;
    private final TerminologyCatalog terminology;
    private final RecordGuard guard;
    private final CaseAccess caseAccess;
    private final OhCaseRepository cases;
    private final CaseTimeline timeline;
    private final DocumentService documents;
    private final EventBus bus;
    private final JsonSupport json;
    private final AuditService audit;
    private final Clock clock;
    private final Duration unsignedAfter;

    public ClinicalEncounterService(ClinicalEncounterRepository encounters, EncounterDiagnosisRepository diagnoses, EncounterObservationRepository observations,
                            EncounterAddendumRepository addenda, EncounterPhotoRepository photos, ResultRepository results,
                            EncounterTemplates templates, EncounterCds cds, JobHistoryService jobs, TerminologyCatalog terminology,
                            RecordGuard guard, CaseAccess caseAccess, OhCaseRepository cases, CaseTimeline timeline, DocumentService documents,
                            EventBus bus, JsonSupport json, AuditService audit, Clock clock,
                            @Value("${wellnessz.record.unsigned-after:PT1H}") Duration unsignedAfter) {
        this.encounters = encounters;
        this.diagnoses = diagnoses;
        this.observations = observations;
        this.addenda = addenda;
        this.photos = photos;
        this.results = results;
        this.templates = templates;
        this.cds = cds;
        this.jobs = jobs;
        this.terminology = terminology;
        this.guard = guard;
        this.caseAccess = caseAccess;
        this.cases = cases;
        this.timeline = timeline;
        this.documents = documents;
        this.bus = bus;
        this.json = json;
        this.audit = audit;
        this.clock = clock;
        this.unsignedAfter = unsignedAfter;
    }

    // ------------------------------------------------------------------ API records

    public record Mechanism(String code, String label) {
    }

    public record EncounterSummary(UUID id, String employeeId, UUID caseId, String type, String status, String title, String author,
                                   Instant createdAt, Instant signedAt, CodeRef primaryDiagnosis, boolean aiAssisted) {
    }

    public record Listing(List<EncounterSummary> items, int restrictedItemsHidden) {
    }

    public record Soap(@Size(max = 20000) String subjective, @Size(max = 20000) String objective, @Size(max = 20000) String assessment,
                       @Size(max = 20000) String plan) {
    }

    public record BodyMapEntry(@NotBlank @Pattern(regexp = REGION_PATTERN) String region,
                               @Pattern(regexp = "LEFT|RIGHT|BILATERAL|MIDLINE") String side, @Pattern(regexp = "FRONT|BACK") String view,
                               @Pattern(regexp = MECHANISM_PATTERN) String mechanism) {
    }

    public record DiagnosisInput(@NotBlank @Pattern(regexp = "SNOMED_CT|ICD_10|ICD_11") String system,
                                 @NotBlank @Pattern(regexp = "[A-Za-z0-9.\\-]{1,50}") String code, @Size(max = 500) String display,
                                 Boolean primary) {
        boolean isPrimary() {
            return Boolean.TRUE.equals(primary);
        }
    }

    public record Diagnosis(String system, String code, String display, boolean primary) {
    }

    public record Addendum(UUID id, String text, String author, Instant signedAt, String sha256) {
    }

    public record Photo(UUID documentId, String bodyRegion, Instant takenAt, String takenBy) {
    }

    public record Signature(String signedBy, Instant signedAt, String sha256) {
    }

    public record EnteredInError(String by, Instant at, String reason) {
    }

    public record EncounterView(UUID id, String employeeId, UUID caseId, String type, int templateVersion, String status, String title,
                                String author, Instant createdAt, Instant signedAt, CodeRef primaryDiagnosis, boolean aiAssisted,
                                Map<String, Object> answers, Map<String, Object> soap, List<BodyMapEntry> bodyMap,
                                List<Diagnosis> diagnoses, List<Addendum> addenda, List<Photo> photos, Signature signature,
                                String classification, EncounterCds.StandardRef fitnessStandard, List<CdsAlert> cdsAlerts,
                                EnteredInError enteredInError) {
    }

    public record CreateRequest(@NotBlank @Pattern(regexp = TYPES) String type, UUID caseId, @Min(1) Integer templateVersion) {
    }

    public record UpdateRequest(@Size(max = 200) Map<String, Object> answers, @Valid Soap soap,
                                @Valid @Size(max = 30) List<BodyMapEntry> bodyMap, @Valid @Size(max = 20) List<DiagnosisInput> diagnoses,
                                @Pattern(regexp = "[A-Z0-9_]{1,40}") String fitnessStandardCode, Boolean aiAssisted) {
    }

    // ------------------------------------------------------------------ create / read

    @Transactional
    public EncounterView create(CurrentUser user, String employeeId, CreateRequest r) {
        boolean restricted = "MENTAL_HEALTH".equals(r.type());
        if (restricted && !user.hasRole(Roles.PSYCH_CLINICIAN)) {
            throw ApiException.forbidden("Mental-health encounters are documented by psychological-health clinicians only");
        }
        DataClassification cls = restricted ? DataClassification.RESTRICTED_CLINICAL : DataClassification.CLINICAL;
        RecordGuard.Access access = guard.write(user, employeeId, cls, AccessPolicy.Action.CREATE, "ENCOUNTER_CREATE");
        EncounterTemplates.Definition t = templates.get(r.type(), r.templateVersion());
        if (r.caseId() != null) {
            OhCase c = caseAccess.clinical(user, r.caseId(), AccessPolicy.Action.UPDATE, "ENCOUNTER_CREATE");
            if (!c.getEmployeeId().equals(employeeId)) {
                throw ApiException.badRequest("case_employee_mismatch", "The case belongs to another employee");
            }
        }
        Instant now = clock.instant();
        ClinicalEncounter e = new ClinicalEncounter();
        e.setId(UUID.randomUUID());
        e.setEmployeeId(employeeId);
        e.setCaseId(r.caseId());
        e.setType(r.type());
        e.setTemplateVersion(t.version());
        e.setStatus("DRAFT");
        e.setClassification(cls.name());
        e.setTitle(t.title());
        e.setAuthor(user.username());
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        e.setAiAssisted(false);
        encounters.save(e);
        if (r.caseId() != null) {
            timeline.record(r.caseId(), "ENCOUNTER_STARTED", "Encounter started (" + r.type() + ")");
        }
        audit.record("ENCOUNTER_CREATE", "ENCOUNTER", e.getId().toString(), AuditService.Outcome.SUCCESS, r.type());
        return view(e, access.employee(), true);
    }

    /** Loads an encounter under ABAC (restricted class → psych / break-glass only); denied → audited 404. */
    public Loaded load(CurrentUser user, UUID id, AccessPolicy.Action action, String auditAction) {
        ClinicalEncounter e = encounters.findById(id).orElseThrow(() -> ApiException.notFound("Encounter"));
        RecordGuard.Access a = action == AccessPolicy.Action.READ
                ? guard.read(user, e.getEmployeeId(), auditAction)
                : guard.write(user, e.getEmployeeId(), DataClassification.valueOf(e.getClassification()), action, auditAction);
        guard.requireVisible(a, e.getClassification(), "ENCOUNTER", id.toString(), auditAction);
        return new Loaded(e, a.employee());
    }

    public record Loaded(ClinicalEncounter encounter, Employee employee) {
    }

    @Transactional
    public EncounterView get(CurrentUser user, UUID id) {
        Loaded l = load(user, id, AccessPolicy.Action.READ, "ENCOUNTER_READ");
        audit.record("ENCOUNTER_READ", "ENCOUNTER", id.toString(), AuditService.Outcome.SUCCESS, null);
        return view(l.encounter(), l.employee(), true);
    }

    @Transactional(readOnly = true)
    public Listing list(CurrentUser user, String employeeId) {
        RecordGuard.Access a = guard.read(user, employeeId, "ENCOUNTER_LIST");
        List<ClinicalEncounter> all = encounters.findByEmployeeIdOrderByCreatedAtDesc(employeeId);
        List<EncounterSummary> visible = all.stream().filter(e -> a.canSee(e.getClassification())).map(ClinicalEncounterService::summary).toList();
        int hidden = all.size() - visible.size();
        audit.record("ENCOUNTER_LIST", "EMPLOYEE", employeeId, AuditService.Outcome.SUCCESS, "rows=" + visible.size() + " hidden=" + hidden);
        return new Listing(visible, hidden);
    }

    /** Reminder list (E03-BL-006): DRAFT encounters older than the threshold – own, or all for NURSING_HEAD / CMO. */
    @Transactional(readOnly = true)
    public List<EncounterSummary> unsigned(CurrentUser user, boolean mine) {
        boolean all = !mine && user.hasAnyRole(Roles.NURSING_HEAD, Roles.CMO);
        Map<String, Boolean> restrictedVisible = new HashMap<>();
        List<EncounterSummary> out = new ArrayList<>();
        for (ClinicalEncounter e : encounters.findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc("DRAFT", clock.instant().minus(unsignedAfter))) {
            if (!all && !e.getAuthor().equals(user.username())) {
                continue;
            }
            if (DataClassification.RESTRICTED_CLINICAL.name().equals(e.getClassification())) {
                boolean ok = restrictedVisible.computeIfAbsent(e.getEmployeeId(),
                        id -> guard.canSeeRestricted(user, guard.employee(id)));
                if (!ok) {
                    continue;
                }
            }
            out.add(summary(e));
        }
        audit.record("ENCOUNTER_UNSIGNED_LIST", "ENCOUNTER", all ? "ALL" : user.username(), AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    static EncounterSummary summary(ClinicalEncounter e) {
        return new EncounterSummary(e.getId(), e.getEmployeeId(), e.getCaseId(), e.getType(), e.getStatus(), e.getTitle(), e.getAuthor(),
                e.getCreatedAt(), e.getSignedAt(), CodeRef.of(e.getPrimaryDiagnosisSystem(), e.getPrimaryDiagnosisCode(),
                e.getPrimaryDiagnosisDisplay()), e.isAiAssisted());
    }

    EncounterView view(ClinicalEncounter e, Employee employee, boolean withCds) {
        EncounterTemplates.Definition t = templates.get(e.getType(), e.getTemplateVersion());
        List<BodyMapEntry> bodyMap = observations.findByEncounterIdAndActiveTrueOrderByPositionAsc(e.getId()).stream()
                .map(o -> new BodyMapEntry(o.getSiteCode(), o.getLaterality(), o.getBodyView(), o.getMechanismCode())).toList();
        List<Diagnosis> dx = diagnoses.findByEncounterIdAndActiveTrueOrderByPositionAsc(e.getId()).stream()
                .map(d -> new Diagnosis(d.getCodeSystem(), d.getCode(), d.getCodeDisplay(), d.isPrimary())).toList();
        List<Addendum> add = addenda.findByEncounterIdOrderBySignedAtAsc(e.getId()).stream()
                .map(a -> new Addendum(a.getId(), a.getText(), a.getAuthor(), a.getSignedAt(), a.getSha256())).toList();
        List<Photo> ph = photos.findByEncounterIdOrderByTakenAtAsc(e.getId()).stream()
                .map(p -> new Photo(p.getDocumentId(), p.getBodyRegion(), p.getTakenAt(), p.getTakenBy())).toList();
        Optional<EncounterCds.StandardRef> standard = cds.standard(t, e.getFitnessStandardCode(), employee);
        List<CdsAlert> alerts = List.of();
        if (withCds && !"ENTERED_IN_ERROR".equals(e.getStatus())) {
            OhCase c = e.getCaseId() == null ? null : cases.findById(e.getCaseId()).orElse(null);
            List<JobHistoryService.SurveillanceDue> due = jobs.surveillance(jobs.sync(employee),
                    encounters.findByEmployeeIdOrderByCreatedAtDesc(e.getEmployeeId()),
                    results.findByEmployeeIdOrderByEffectiveAtDesc(e.getEmployeeId()));
            alerts = cds.alerts(t, standard, c, due);
        }
        return new EncounterView(e.getId(), e.getEmployeeId(), e.getCaseId(), e.getType(), e.getTemplateVersion(), e.getStatus(),
                e.getTitle(), e.getAuthor(), e.getCreatedAt(), e.getSignedAt(),
                CodeRef.of(e.getPrimaryDiagnosisSystem(), e.getPrimaryDiagnosisCode(), e.getPrimaryDiagnosisDisplay()), e.isAiAssisted(),
                json.map(e.getAnswers()), json.map(e.getSoap()), bodyMap, dx, add, ph,
                e.getSignedAt() == null ? null : new Signature(e.getSignedBy(), e.getSignedAt(), e.getSignatureSha256()),
                e.getClassification(), standard.orElse(null), alerts,
                e.getEieAt() == null ? null : new EnteredInError(e.getEieBy(), e.getEieAt(), e.getEieReason()));
    }

    // ------------------------------------------------------------------ edit (author while DRAFT)

    @Transactional
    public EncounterView update(CurrentUser user, UUID id, UpdateRequest r) {
        Loaded l = load(user, id, AccessPolicy.Action.UPDATE, "ENCOUNTER_UPDATE");
        ClinicalEncounter e = l.encounter();
        requireAuthor(user, e);
        if (!"DRAFT".equals(e.getStatus())) {
            throw ApiException.conflict("encounter_locked", "Signed encounters are immutable – add an addendum instead");
        }
        EncounterTemplates.Definition t = templates.get(e.getType(), e.getTemplateVersion());
        Instant now = clock.instant();
        if (r.answers() != null) {
            e.setAnswers(json.write(validateAnswers(t, r.answers())));
        }
        if (r.soap() != null) {
            Map<String, Object> soap = new LinkedHashMap<>(json.map(e.getSoap()));
            putIfPresent(soap, "subjective", r.soap().subjective());
            putIfPresent(soap, "objective", r.soap().objective());
            putIfPresent(soap, "assessment", r.soap().assessment());
            putIfPresent(soap, "plan", r.soap().plan());
            e.setSoap(json.write(soap));
        }
        if (r.bodyMap() != null) {
            if (!t.bodyMap() && !r.bodyMap().isEmpty()) {
                throw ApiException.badRequest("body_map_not_supported", "This template has no body map");
            }
            observations.findByEncounterIdAndActiveTrueOrderByPositionAsc(id).forEach(o -> o.setActive(false));
            int pos = 0;
            for (BodyMapEntry b : r.bodyMap()) {
                EncounterObservation o = new EncounterObservation();
                o.setId(UUID.randomUUID());
                o.setEncounterId(id);
                o.setKind("BODY_SITE");
                o.setCodeSystem("WZ_BODY_MAP");
                o.setSiteCode(b.region());
                o.setLaterality(b.side());
                o.setBodyView(b.view());
                o.setMechanismCode(b.mechanism());
                o.setPosition(pos++);
                o.setActive(true);
                observations.save(o);
            }
        }
        if (r.diagnoses() != null) {
            codeDiagnoses(user, e, r.diagnoses(), now);
        }
        if (r.fitnessStandardCode() != null) {
            cds.standard(t, r.fitnessStandardCode(), l.employee())
                    .orElseThrow(() -> ApiException.badRequest("unknown_standard", "Unknown or inactive fitness standard"));
            e.setFitnessStandardCode(r.fitnessStandardCode());
        }
        if (Boolean.TRUE.equals(r.aiAssisted()) && !e.isAiAssisted()) {
            e.setAiAssisted(true);
            audit.record("ENCOUNTER_AI_ASSISTED", "ENCOUNTER", id.toString(), AuditService.Outcome.SUCCESS,
                    "Clinician saved content from an AI draft");
        }
        e.setUpdatedAt(now);
        audit.record("ENCOUNTER_UPDATE", "ENCOUNTER", id.toString(), AuditService.Outcome.SUCCESS, null);
        return view(e, l.employee(), true);
    }

    private static void putIfPresent(Map<String, Object> m, String key, String value) {
        if (value != null) {
            m.put(key, value);
        }
    }

    private void codeDiagnoses(CurrentUser user, ClinicalEncounter e, List<DiagnosisInput> input, Instant now) {
        diagnoses.findByEncounterIdAndActiveTrueOrderByPositionAsc(e.getId()).forEach(d -> d.setActive(false));
        boolean anyPrimary = input.stream().anyMatch(DiagnosisInput::isPrimary);
        CodeRef primary = null;
        int pos = 0;
        for (DiagnosisInput in : input) {
            CodeRef c = terminology.require(new CodeRef(in.system(), in.code(), in.display()));
            boolean isPrimary = anyPrimary ? in.isPrimary() && primary == null : pos == 0;
            EncounterDiagnosis d = new EncounterDiagnosis();
            d.setId(UUID.randomUUID());
            d.setEncounterId(e.getId());
            d.setCodeSystem(c.system());
            d.setCode(c.code());
            d.setCodeDisplay(c.display());
            d.setPrimary(isPrimary);
            d.setPosition(pos++);
            d.setActive(true);
            diagnoses.save(d);
            terminology.recordUsage(user.username(), c.system(), c.code());
            if (isPrimary) {
                primary = c;
            }
        }
        e.setPrimaryDiagnosisSystem(primary == null ? null : primary.system());
        e.setPrimaryDiagnosisCode(primary == null ? null : primary.code());
        e.setPrimaryDiagnosisDisplay(primary == null ? null : primary.display());
        if (primary != null && e.getCaseId() != null) {
            OhCase c = caseAccess.clinical(user, e.getCaseId(), AccessPolicy.Action.UPDATE, "CASE_DIAGNOSIS_CODE");
            c.codePrimaryDiagnosis(primary.system(), primary.code(), primary.display(), now);
            cases.save(c);
            timeline.record(c.getId(), "DIAGNOSIS_CODED", "Primary diagnosis coded on encounter");
        }
    }

    /** Validates answer keys against the template (unknown keys rejected) and option values against coded pick-lists. */
    Map<String, Object> validateAnswers(EncounterTemplates.Definition t, Map<String, Object> answers) {
        Map<String, EncounterTemplates.Field> fields = new HashMap<>();
        t.sections().stream().filter(s -> !"soap".equals(s.key())).forEach(s -> s.fields().forEach(f -> fields.put(f.key(), f)));
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> a : answers.entrySet()) {
            EncounterTemplates.Field f = fields.get(a.getKey());
            if (f == null) {
                throw ApiException.badRequest("unknown_field", "Unknown template field: " + safeKey(a.getKey()));
            }
            Object v = a.getValue();
            if (v == null) {
                continue;
            }
            switch (f.type()) {
                case "NUMBER" -> {
                    if (!(v instanceof Number)) {
                        throw ApiException.badRequest("invalid_value", "Field " + f.key() + " must be a number");
                    }
                }
                case "BOOLEAN" -> {
                    if (!(v instanceof Boolean)) {
                        throw ApiException.badRequest("invalid_value", "Field " + f.key() + " must be true or false");
                    }
                }
                case "MULTISELECT" -> {
                    if (!(v instanceof Collection<?> c) || c.size() > 30 || !c.stream().allMatch(x -> isOption(f, x))) {
                        throw ApiException.badRequest("invalid_value", "Field " + f.key() + " must be a list of allowed values");
                    }
                }
                case "SELECT" -> {
                    if (!isOption(f, v)) {
                        throw ApiException.badRequest("invalid_value", "Field " + f.key() + " has a value outside the pick-list");
                    }
                }
                default -> {
                    if (!(v instanceof String s) || s.length() > MAX_ANSWER_LENGTH) {
                        throw ApiException.badRequest("invalid_value", "Field " + f.key() + " must be text of at most " + MAX_ANSWER_LENGTH
                                + " characters");
                    }
                }
            }
            out.put(f.key(), v);
        }
        return out;
    }

    private static boolean isOption(EncounterTemplates.Field f, Object v) {
        return v instanceof String s && f.options() != null && f.options().stream().anyMatch(o -> o.value().equals(s));
    }

    private static String safeKey(String key) {
        String k = key.length() > 40 ? key.substring(0, 40) : key;
        return k.replaceAll("[^A-Za-z0-9_]", "?");
    }

    private static void requireAuthor(CurrentUser user, ClinicalEncounter e) {
        if (!e.getAuthor().equals(user.username())) {
            throw ApiException.forbidden("Only the author may change or sign this draft");
        }
    }

    // ------------------------------------------------------------------ sign, addenda, entered-in-error

    @Transactional
    public EncounterView sign(CurrentUser user, UUID id) {
        Loaded l = load(user, id, AccessPolicy.Action.UPDATE, "ENCOUNTER_SIGN");
        ClinicalEncounter e = l.encounter();
        requireAuthor(user, e);
        if (!"DRAFT".equals(e.getStatus())) {
            throw ApiException.conflict("not_draft", "Only draft encounters can be signed");
        }
        EncounterTemplates.Definition t = templates.get(e.getType(), e.getTemplateVersion());
        Map<String, Object> answers = json.map(e.getAnswers());
        Map<String, Object> soap = json.map(e.getSoap());
        List<String> missing = missingFields(t, answers, soap);
        if (!missing.isEmpty()) {
            throw RecordProblem.missingFields(missing);
        }
        List<EncounterDiagnosis> dx = diagnoses.findByEncounterIdAndActiveTrueOrderByPositionAsc(id);
        if (t.requiresDiagnosis() && dx.isEmpty()) {
            throw RecordProblem.unprocessable("diagnosis_required", "Code at least one diagnosis before signing");
        }
        List<EncounterObservation> body = observations.findByEncounterIdAndActiveTrueOrderByPositionAsc(id);
        if (t.bodyMapRequired() && body.isEmpty()) {
            throw RecordProblem.unprocessable("body_map_required", "Mark the injury site and mechanism on the body map");
        }
        Instant now = clock.instant();
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("id", id.toString());
        canonical.put("employeeId", e.getEmployeeId());
        canonical.put("caseId", e.getCaseId() == null ? null : e.getCaseId().toString());
        canonical.put("type", e.getType());
        canonical.put("templateVersion", e.getTemplateVersion());
        canonical.put("answers", answers);
        canonical.put("soap", soap);
        canonical.put("bodyMap", body.stream().map(o -> o.getSiteCode() + "|" + o.getLaterality() + "|" + o.getBodyView() + "|"
                + o.getMechanismCode()).toList());
        canonical.put("diagnoses", dx.stream().map(d -> d.getCodeSystem() + "|" + d.getCode() + "|" + d.isPrimary()).toList());
        canonical.put("signedBy", user.username());
        canonical.put("signedAt", now.toString());
        e.setSignatureSha256(sha256(json.write(canonical)));
        e.setSignedBy(user.username());
        e.setSignedAt(now);
        e.setStatus("SIGNED");
        e.setUpdatedAt(now);
        bus.publish(new E03Events.EncounterSigned(id.toString(), e.getEmployeeId(), e.getCaseId() == null ? null : e.getCaseId().toString(),
                e.getType()));
        if (e.getCaseId() != null) {
            timeline.record(e.getCaseId(), "ENCOUNTER_SIGNED", "Encounter signed (" + e.getType() + ")");
        }
        audit.record("ENCOUNTER_SIGN", "ENCOUNTER", id.toString(), AuditService.Outcome.SUCCESS, e.getSignatureSha256());
        return view(e, l.employee(), true);
    }

    static List<String> missingFields(EncounterTemplates.Definition t, Map<String, Object> answers, Map<String, Object> soap) {
        List<String> missing = new ArrayList<>();
        for (EncounterTemplates.Section s : t.sections()) {
            Map<String, Object> source = "soap".equals(s.key()) ? soap : answers;
            for (EncounterTemplates.Field f : s.fields()) {
                if (f.required() && isBlank(source.get(f.key()))) {
                    missing.add(("soap".equals(s.key()) ? "soap." : "") + f.key());
                }
            }
        }
        return missing;
    }

    private static boolean isBlank(Object v) {
        return v == null || v instanceof String s && s.isBlank() || v instanceof Collection<?> c && c.isEmpty();
    }

    public record AddendumRequest(@NotBlank @Size(max = 10000) String text) {
    }

    @Transactional
    public EncounterView addAddendum(CurrentUser user, UUID id, AddendumRequest r) {
        Loaded l = load(user, id, AccessPolicy.Action.UPDATE, "ENCOUNTER_ADDENDUM");
        ClinicalEncounter e = l.encounter();
        if (!"SIGNED".equals(e.getStatus())) {
            throw ApiException.conflict("not_signed", "Addenda can only be added to signed encounters");
        }
        Instant now = clock.instant();
        EncounterAddendum a = new EncounterAddendum();
        a.setId(UUID.randomUUID());
        a.setEncounterId(id);
        a.setText(r.text());
        a.setAuthor(user.username());
        a.setSignedAt(now);
        a.setSha256(sha256(e.getSignatureSha256() + "|" + r.text() + "|" + user.username() + "|" + now));
        addenda.save(a);
        audit.record("ENCOUNTER_ADDENDUM", "ENCOUNTER", id.toString(), AuditService.Outcome.SUCCESS, a.getSha256());
        return view(e, l.employee(), false);
    }

    public record ErrorRequest(@NotBlank @Size(min = 5, max = 1000) String reason) {
    }

    @Transactional
    public EncounterView enteredInError(CurrentUser user, UUID id, ErrorRequest r) {
        Loaded l = load(user, id, AccessPolicy.Action.UPDATE, "ENCOUNTER_ENTERED_IN_ERROR");
        ClinicalEncounter e = l.encounter();
        if (!e.getAuthor().equals(user.username()) && !user.hasRole(Roles.CMO)) {
            throw ApiException.forbidden("Only the author or the CMO may mark an encounter as entered in error");
        }
        if ("ENTERED_IN_ERROR".equals(e.getStatus())) {
            throw ApiException.conflict("already_entered_in_error", "Encounter is already marked as entered in error");
        }
        Instant now = clock.instant();
        e.setStatus("ENTERED_IN_ERROR");
        e.setEieBy(user.username());
        e.setEieAt(now);
        e.setEieReason(r.reason());
        e.setUpdatedAt(now);
        if (e.getCaseId() != null) {
            timeline.record(e.getCaseId(), "ENCOUNTER_ENTERED_IN_ERROR", "Encounter marked as entered in error");
        }
        audit.record("ENCOUNTER_ENTERED_IN_ERROR", "ENCOUNTER", id.toString(), AuditService.Outcome.SUCCESS, null);
        return view(e, l.employee(), false);
    }

    // ------------------------------------------------------------------ photos (E03-BL-007)

    public record PhotoResult(UUID documentId) {
    }

    @Transactional
    public PhotoResult addPhoto(CurrentUser user, UUID id, byte[] bytes, boolean consent, String bodyRegion) {
        if (!consent) {
            throw ApiException.badRequest("consent_required", "Record the employee's consent to clinical photography (consent=true)");
        }
        if (bodyRegion != null && !REGIONS.contains(bodyRegion)) {
            throw ApiException.badRequest("invalid_region", "Unknown body region");
        }
        Loaded l = load(user, id, AccessPolicy.Action.UPDATE, "ENCOUNTER_PHOTO");
        ClinicalEncounter e = l.encounter();
        if ("ENTERED_IN_ERROR".equals(e.getStatus())) {
            throw ApiException.conflict("entered_in_error", "Encounter was entered in error");
        }
        DocumentService.DocumentView doc = documents.storePhoto(user, e.getEmployeeId(), e.getCaseId(), bytes,
                "Clinical photograph" + (bodyRegion == null ? "" : " – " + bodyRegion.toLowerCase(java.util.Locale.ROOT).replace('_', ' ')),
                DataClassification.valueOf(e.getClassification()));
        EncounterPhoto p = new EncounterPhoto();
        p.setId(UUID.randomUUID());
        p.setEncounterId(id);
        p.setDocumentId(doc.id());
        p.setBodyRegion(bodyRegion);
        p.setTakenAt(clock.instant());
        p.setTakenBy(user.username());
        p.setConsentRecorded(true);
        photos.save(p);
        audit.record("ENCOUNTER_PHOTO", "ENCOUNTER", id.toString(), AuditService.Outcome.SUCCESS, "document=" + doc.id());
        return new PhotoResult(doc.id());
    }

    // ------------------------------------------------------------------ helpers for other E03 services

    @Transactional(readOnly = true)
    public List<ClinicalEncounter> forCase(UUID caseId) {
        return encounters.findByCaseIdOrderByCreatedAtDesc(caseId);
    }

    @Transactional(readOnly = true)
    public List<Diagnosis> diagnosesOf(UUID encounterId) {
        return diagnoses.findByEncounterIdAndActiveTrueOrderByPositionAsc(encounterId).stream()
                .map(d -> new Diagnosis(d.getCodeSystem(), d.getCode(), d.getCodeDisplay(), d.isPrimary())).toList();
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
