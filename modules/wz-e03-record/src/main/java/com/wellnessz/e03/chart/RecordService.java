package com.wellnessz.e03.chart;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.e03.cds.CdsAlert;
import com.wellnessz.e03.cds.CdsOverride;
import com.wellnessz.e03.cds.CdsService;
import com.wellnessz.e03.model.Allergy;
import com.wellnessz.e03.model.AllergyRepository;
import com.wellnessz.e03.model.Immunisation;
import com.wellnessz.e03.model.ImmunisationRepository;
import com.wellnessz.e03.model.Medication;
import com.wellnessz.e03.model.MedicationRepository;
import com.wellnessz.e03.model.Problem;
import com.wellnessz.e03.model.ProblemRepository;
import com.wellnessz.e03.support.CodeRef;
import com.wellnessz.e03.support.JsonSupport;
import com.wellnessz.e03.support.RecordGuard;
import com.wellnessz.e03.terminology.TerminologyCatalog;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/** Problem list, allergies, medications (with CDS) and immunisations of the OH chart (E03-F01, FHIR-aligned). */
@Service
public class RecordService {

    private final ProblemRepository problems;
    private final AllergyRepository allergies;
    private final MedicationRepository medications;
    private final ImmunisationRepository immunisations;
    private final RecordGuard guard;
    private final TerminologyCatalog terminology;
    private final CdsService cds;
    private final JsonSupport json;
    private final AuditService audit;
    private final Clock clock;

    public RecordService(ProblemRepository problems, AllergyRepository allergies, MedicationRepository medications,
                         ImmunisationRepository immunisations, RecordGuard guard, TerminologyCatalog terminology, CdsService cds,
                         JsonSupport json, AuditService audit, Clock clock) {
        this.problems = problems;
        this.allergies = allergies;
        this.medications = medications;
        this.immunisations = immunisations;
        this.guard = guard;
        this.terminology = terminology;
        this.cds = cds;
        this.json = json;
        this.audit = audit;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ views & requests

    public record ProblemView(UUID id, String employeeId, CodeRef code, String status, LocalDate onsetDate, LocalDate resolvedDate,
                              String workRelated, UUID caseId, String note, String classification, String recordedBy, Instant recordedAt) {
        static ProblemView of(Problem p) {
            return new ProblemView(p.getId(), p.getEmployeeId(), CodeRef.of(p.getCodeSystem(), p.getCode(), p.getCodeDisplay()),
                    p.getStatus(), p.getOnsetDate(), p.getResolvedDate(), p.getWorkRelated(), p.getCaseId(), p.getNote(),
                    p.getClassification(), p.getRecordedBy(), p.getRecordedAt());
        }
    }

    public record ProblemRequest(@NotNull @Valid CodeRef code, @Pattern(regexp = "ACTIVE|RESOLVED|INACTIVE") String status,
                                 LocalDate onsetDate, LocalDate resolvedDate, @Pattern(regexp = "YES|NO|UNDER_REVIEW") String workRelated,
                                 UUID caseId, @Size(max = 4000) String note, Boolean restricted) {
    }

    public record ProblemUpdate(@Pattern(regexp = "ACTIVE|RESOLVED|INACTIVE") String status, LocalDate resolvedDate,
                                @Pattern(regexp = "YES|NO|UNDER_REVIEW") String workRelated, @Size(max = 4000) String note) {
    }

    public record Substance(@Pattern(regexp = "SNOMED_CT|ICD_10|ICD_11|LOINC|LOCAL") String system,
                            @Pattern(regexp = "[A-Za-z0-9.\\-]{1,50}") String code, @NotBlank @Size(max = 200) String display) {
    }

    public record AllergyView(UUID id, String employeeId, Substance substance, String category, String criticality, String reaction,
                              String status, String recordedBy, Instant recordedAt) {
        static AllergyView of(Allergy a) {
            return new AllergyView(a.getId(), a.getEmployeeId(), new Substance(a.getSubstanceSystem(), a.getSubstanceCode(),
                    a.getSubstanceDisplay()), a.getCategory(), a.getCriticality(), a.getReaction(), a.getStatus(), a.getRecordedBy(),
                    a.getRecordedAt());
        }
    }

    public record AllergyRequest(@NotNull @Valid Substance substance, @NotBlank @Pattern(regexp = "MEDICATION|FOOD|ENVIRONMENT|BIOLOGIC") String category,
                                 @NotBlank @Pattern(regexp = "LOW|HIGH|UNABLE_TO_ASSESS") String criticality,
                                 @Size(max = 1000) String reaction) {
    }

    public record AllergyUpdate(@Pattern(regexp = "MEDICATION|FOOD|ENVIRONMENT|BIOLOGIC") String category,
                                @Pattern(regexp = "LOW|HIGH|UNABLE_TO_ASSESS") String criticality, @Size(max = 1000) String reaction,
                                @Pattern(regexp = "ACTIVE|INACTIVE|ENTERED_IN_ERROR") String status) {
    }

    public record MedicationView(UUID id, String employeeId, String name, CodeRef code, String dose, String route, String frequency,
                                 String source, String status, LocalDate startDate, LocalDate endDate, String recordedBy,
                                 Instant recordedAt, List<CdsOverride> overrides) {
    }

    public record MedicationRequest(@NotBlank @Size(max = 200) String name, @Valid CodeRef code, @Size(max = 200) String dose,
                                    @Pattern(regexp = "[A-Z_]{1,40}") String route, @Size(max = 100) String frequency,
                                    @NotBlank @Pattern(regexp = "REPORTED|ADMINISTERED") String source,
                                    @Pattern(regexp = "ACTIVE|COMPLETED|STOPPED") String status, LocalDate startDate, LocalDate endDate,
                                    @Valid @Size(max = 20) List<CdsOverride> overrides) {
    }

    public record MedicationUpdate(@Size(max = 200) String dose, @Pattern(regexp = "[A-Z_]{1,40}") String route,
                                   @Size(max = 100) String frequency, @Pattern(regexp = "ACTIVE|COMPLETED|STOPPED") String status,
                                   LocalDate endDate) {
    }

    public record ImmunisationView(UUID id, String employeeId, String vaccine, CodeRef code, LocalDate date, Integer doseNumber,
                                   String lotNumber, String status, LocalDate nextDueDate) {
        static ImmunisationView of(Immunisation i) {
            return new ImmunisationView(i.getId(), i.getEmployeeId(), i.getVaccine(), CodeRef.of(i.getCodeSystem(), i.getCode(), null),
                    i.getAdministeredOn(), i.getDoseNumber(), i.getLotNumber(), i.getStatus(), i.getNextDueDate());
        }
    }

    public record ImmunisationRequest(@NotBlank @Size(max = 200) String vaccine, @Valid CodeRef code, @NotNull LocalDate date,
                                      @Min(1) @Max(10) Integer doseNumber, @Size(max = 60) String lotNumber,
                                      @NotBlank @Pattern(regexp = "COMPLETE|PARTIAL|DUE") String status, LocalDate nextDueDate) {
    }

    // ------------------------------------------------------------------ problems

    @Transactional(readOnly = true)
    public List<ProblemView> problems(CurrentUser user, String employeeId) {
        RecordGuard.Access a = guard.read(user, employeeId, "CHART_PROBLEMS_READ");
        List<ProblemView> out = problems.findByEmployeeIdOrderByRecordedAtDesc(employeeId).stream()
                .filter(p -> a.canSee(p.getClassification())).map(ProblemView::of).toList();
        audit.record("CHART_PROBLEMS_READ", "EMPLOYEE", employeeId, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    @Transactional
    public ProblemView addProblem(CurrentUser user, String employeeId, ProblemRequest r) {
        DataClassification cls = Boolean.TRUE.equals(r.restricted()) ? DataClassification.RESTRICTED_CLINICAL : DataClassification.CLINICAL;
        guard.write(user, employeeId, cls, AccessPolicy.Action.CREATE, "CHART_PROBLEM_CREATE");
        CodeRef code = terminology.require(r.code());
        Instant now = clock.instant();
        Problem p = new Problem();
        p.setId(UUID.randomUUID());
        p.setEmployeeId(employeeId);
        p.setCodeSystem(code.system());
        p.setCode(code.code());
        p.setCodeDisplay(code.display());
        p.setStatus(r.status() == null ? "ACTIVE" : r.status());
        p.setOnsetDate(r.onsetDate());
        p.setResolvedDate(r.resolvedDate());
        p.setWorkRelated(r.workRelated() == null ? "UNDER_REVIEW" : r.workRelated());
        p.setCaseId(r.caseId());
        p.setNote(r.note());
        p.setClassification(cls.name());
        p.setRecordedBy(user.username());
        p.setRecordedAt(now);
        p.setUpdatedAt(now);
        problems.save(p);
        terminology.recordUsage(user.username(), code.system(), code.code());
        audit.record("CHART_PROBLEM_CREATE", "PROBLEM", p.getId().toString(), AuditService.Outcome.SUCCESS, null);
        return ProblemView.of(p);
    }

    @Transactional
    public ProblemView updateProblem(CurrentUser user, UUID id, ProblemUpdate r) {
        Problem p = problems.findById(id).orElseThrow(() -> ApiException.notFound("Problem"));
        guard.write(user, p.getEmployeeId(), DataClassification.valueOf(p.getClassification()), AccessPolicy.Action.UPDATE,
                "CHART_PROBLEM_UPDATE");
        if (r.status() != null) {
            p.setStatus(r.status());
            if ("RESOLVED".equals(r.status()) && r.resolvedDate() == null && p.getResolvedDate() == null) {
                p.setResolvedDate(LocalDate.ofInstant(clock.instant(), java.time.ZoneOffset.UTC));
            }
        }
        if (r.resolvedDate() != null) {
            p.setResolvedDate(r.resolvedDate());
        }
        if (r.workRelated() != null) {
            p.setWorkRelated(r.workRelated());
        }
        if (r.note() != null) {
            p.setNote(r.note());
        }
        p.setUpdatedAt(clock.instant());
        audit.record("CHART_PROBLEM_UPDATE", "PROBLEM", id.toString(), AuditService.Outcome.SUCCESS, null);
        return ProblemView.of(p);
    }

    // ------------------------------------------------------------------ allergies

    @Transactional(readOnly = true)
    public List<AllergyView> allergies(CurrentUser user, String employeeId) {
        guard.read(user, employeeId, "CHART_ALLERGIES_READ");
        List<AllergyView> out = allergies.findByEmployeeIdOrderByRecordedAtDesc(employeeId).stream().map(AllergyView::of).toList();
        audit.record("CHART_ALLERGIES_READ", "EMPLOYEE", employeeId, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    @Transactional
    public AllergyView addAllergy(CurrentUser user, String employeeId, AllergyRequest r) {
        guard.write(user, employeeId, DataClassification.CLINICAL, AccessPolicy.Action.CREATE, "CHART_ALLERGY_CREATE");
        Substance s = r.substance();
        String display = s.display();
        if (s.system() != null && s.code() != null) {
            CodeRef c = terminology.require(new CodeRef(s.system(), s.code(), s.display()));
            display = c.display();
            terminology.recordUsage(user.username(), c.system(), c.code());
        }
        Instant now = clock.instant();
        Allergy a = new Allergy();
        a.setId(UUID.randomUUID());
        a.setEmployeeId(employeeId);
        a.setSubstanceSystem(s.code() == null ? null : s.system());
        a.setSubstanceCode(s.system() == null ? null : s.code());
        a.setSubstanceDisplay(display);
        a.setCategory(r.category());
        a.setCriticality(r.criticality());
        a.setReaction(r.reaction());
        a.setStatus("ACTIVE");
        a.setRecordedBy(user.username());
        a.setRecordedAt(now);
        a.setUpdatedAt(now);
        allergies.save(a);
        audit.record("CHART_ALLERGY_CREATE", "ALLERGY", a.getId().toString(), AuditService.Outcome.SUCCESS, null);
        return AllergyView.of(a);
    }

    @Transactional
    public AllergyView updateAllergy(CurrentUser user, UUID id, AllergyUpdate r) {
        Allergy a = allergies.findById(id).orElseThrow(() -> ApiException.notFound("Allergy"));
        guard.write(user, a.getEmployeeId(), DataClassification.CLINICAL, AccessPolicy.Action.UPDATE, "CHART_ALLERGY_UPDATE");
        if (r.category() != null) {
            a.setCategory(r.category());
        }
        if (r.criticality() != null) {
            a.setCriticality(r.criticality());
        }
        if (r.reaction() != null) {
            a.setReaction(r.reaction());
        }
        if (r.status() != null) {
            a.setStatus(r.status());
        }
        a.setUpdatedAt(clock.instant());
        audit.record("CHART_ALLERGY_UPDATE", "ALLERGY", id.toString(), AuditService.Outcome.SUCCESS, null);
        return AllergyView.of(a);
    }

    // ------------------------------------------------------------------ medications (CDS, E03-BL-013)

    MedicationView view(Medication m) {
        return new MedicationView(m.getId(), m.getEmployeeId(), m.getName(), CodeRef.of(m.getCodeSystem(), m.getCode(), m.getCodeDisplay()),
                m.getDose(), m.getRoute(), m.getFrequency(), m.getSource(), m.getStatus(), m.getStartDate(), m.getEndDate(),
                m.getRecordedBy(), m.getRecordedAt(), json.readList(m.getOverrides(), CdsOverride.class));
    }

    @Transactional(readOnly = true)
    public List<MedicationView> medications(CurrentUser user, String employeeId) {
        RecordGuard.Access a = guard.read(user, employeeId, "CHART_MEDICATIONS_READ");
        List<MedicationView> out = medications.findByEmployeeIdOrderByRecordedAtDesc(employeeId).stream()
                .filter(m -> a.canSee(m.getClassification())).map(this::view).toList();
        audit.record("CHART_MEDICATIONS_READ", "EMPLOYEE", employeeId, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    /** Records a medication; allergy-class and interaction checks block with 409 unless every alert is overridden with a reason. */
    @Transactional
    public MedicationView addMedication(CurrentUser user, String employeeId, MedicationRequest r) {
        guard.write(user, employeeId, DataClassification.CLINICAL, AccessPolicy.Action.CREATE, "CHART_MEDICATION_CREATE");
        String label = r.name() + (r.code() != null && r.code().display() != null ? " " + r.code().display() : "");
        List<CdsService.Substance> allergyList = allergies.findByEmployeeIdOrderByRecordedAtDesc(employeeId).stream()
                .filter(x -> "ACTIVE".equals(x.getStatus()))
                .map(x -> new CdsService.Substance(x.getSubstanceDisplay(), x.getSubstanceCode())).toList();
        List<String> active = medications.findByEmployeeIdOrderByRecordedAtDesc(employeeId).stream()
                .filter(m -> "ACTIVE".equals(m.getStatus())).map(Medication::getName).toList();
        List<CdsAlert> alerts = cds.medicationAlerts(label, allergyList, active);
        Map<String, String> applied = cds.enforce(employeeId, "MEDICATION", user.username(), alerts, r.overrides());
        Instant now = clock.instant();
        Medication m = new Medication();
        m.setId(UUID.randomUUID());
        m.setEmployeeId(employeeId);
        m.setName(r.name());
        if (r.code() != null) {
            m.setCodeSystem(r.code().system());
            m.setCode(r.code().code());
            m.setCodeDisplay(r.code().display());
        }
        m.setDose(r.dose());
        m.setRoute(r.route());
        m.setFrequency(r.frequency());
        m.setSource(r.source());
        m.setStatus(r.status() == null ? ("ADMINISTERED".equals(r.source()) ? "COMPLETED" : "ACTIVE") : r.status());
        m.setStartDate(r.startDate() == null ? LocalDate.ofInstant(now, java.time.ZoneOffset.UTC) : r.startDate());
        m.setEndDate(r.endDate());
        m.setClassification(DataClassification.CLINICAL.name());
        m.setOverrides(applied.isEmpty() ? null : json.write(applied.entrySet().stream()
                .map(e -> new CdsOverride(e.getKey(), e.getValue())).toList()));
        m.setRecordedBy(user.username());
        m.setRecordedAt(now);
        m.setUpdatedAt(now);
        medications.save(m);
        audit.record("CHART_MEDICATION_CREATE", "MEDICATION", m.getId().toString(), AuditService.Outcome.SUCCESS,
                "source=" + r.source() + " cdsAlerts=" + alerts.size() + " overridden=" + applied.size());
        return view(m);
    }

    @Transactional
    public MedicationView updateMedication(CurrentUser user, UUID id, MedicationUpdate r) {
        Medication m = medications.findById(id).orElseThrow(() -> ApiException.notFound("Medication"));
        guard.write(user, m.getEmployeeId(), DataClassification.valueOf(m.getClassification()), AccessPolicy.Action.UPDATE,
                "CHART_MEDICATION_UPDATE");
        if (r.dose() != null) {
            m.setDose(r.dose());
        }
        if (r.route() != null) {
            m.setRoute(r.route());
        }
        if (r.frequency() != null) {
            m.setFrequency(r.frequency());
        }
        if (r.status() != null) {
            m.setStatus(r.status());
        }
        if (r.endDate() != null) {
            m.setEndDate(r.endDate());
        }
        m.setUpdatedAt(clock.instant());
        audit.record("CHART_MEDICATION_UPDATE", "MEDICATION", id.toString(), AuditService.Outcome.SUCCESS, null);
        return view(m);
    }

    // ------------------------------------------------------------------ immunisations

    @Transactional(readOnly = true)
    public List<ImmunisationView> immunisations(CurrentUser user, String employeeId) {
        guard.read(user, employeeId, "CHART_IMMUNISATIONS_READ");
        List<ImmunisationView> out = immunisations.findByEmployeeIdOrderByAdministeredOnDesc(employeeId).stream()
                .map(ImmunisationView::of).toList();
        audit.record("CHART_IMMUNISATIONS_READ", "EMPLOYEE", employeeId, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    @Transactional
    public ImmunisationView addImmunisation(CurrentUser user, String employeeId, ImmunisationRequest r) {
        guard.write(user, employeeId, DataClassification.CLINICAL, AccessPolicy.Action.CREATE, "CHART_IMMUNISATION_CREATE");
        Immunisation i = new Immunisation();
        i.setId(UUID.randomUUID());
        i.setEmployeeId(employeeId);
        i.setVaccine(r.vaccine());
        if (r.code() != null) {
            i.setCodeSystem(r.code().system());
            i.setCode(r.code().code());
        }
        i.setAdministeredOn(r.date());
        i.setDoseNumber(r.doseNumber());
        i.setLotNumber(r.lotNumber());
        i.setStatus(r.status());
        i.setNextDueDate(r.nextDueDate());
        i.setRecordedBy(user.username());
        i.setRecordedAt(clock.instant());
        immunisations.save(i);
        audit.record("CHART_IMMUNISATION_CREATE", "IMMUNISATION", i.getId().toString(), AuditService.Outcome.SUCCESS, null);
        return ImmunisationView.of(i);
    }
}
