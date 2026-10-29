package com.wellnessz.e07.exposure;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.CaseService;
import com.wellnessz.casekernel.model.CaseCategory;
import com.wellnessz.casekernel.model.CaseSource;
import com.wellnessz.casekernel.model.CaseType;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OpenCaseCommand;
import com.wellnessz.casekernel.support.EmployeeRecordAccess;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.common.model.Roles;
import com.wellnessz.e07.model.CosmicDose;
import com.wellnessz.e07.model.CosmicDoseRepository;
import com.wellnessz.e07.model.ExposureGroup;
import com.wellnessz.e07.model.ExposureGroupRepository;
import com.wellnessz.e07.model.IhMeasurement;
import com.wellnessz.e07.model.IhMeasurementRepository;
import com.wellnessz.e07.model.PregnancyDeclaration;
import com.wellnessz.e07.model.PregnancyDeclarationRepository;
import com.wellnessz.e07.model.Programme;
import com.wellnessz.e07.programme.Eligibility;
import com.wellnessz.e07.programme.EnrolmentEngine;
import com.wellnessz.e07.programme.Population;
import com.wellnessz.e07.programme.ProgrammeCatalog;
import com.wellnessz.e07.support.SurveillanceSupport;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Exposure registers & IH linkage (E07-F06): similar exposure groups, HSE measurement import with automatic enrolment of the
 * SEG's job codes into the hazard's programme when the action level is reached, cosmic dose of crew with AME review alerts,
 * pregnancy declarations of crew (AME / CMO only) and the personal exposure history.
 */
@Service
public class ExposureService {

    static final int MAX_ROWS = 5000;
    public static final BigDecimal PREGNANCY_DOSE_LIMIT_MSV = BigDecimal.ONE;

    private final ExposureGroupRepository segs;
    private final IhMeasurementRepository measurements;
    private final CosmicDoseRepository doses;
    private final PregnancyDeclarationRepository pregnancies;
    private final Population population;
    private final ProgrammeCatalog catalog;
    private final EnrolmentEngine engine;
    private final CaseService cases;
    private final EmployeeRecordAccess records;
    private final SurveillanceSupport support;
    private final AuditService audit;

    public ExposureService(ExposureGroupRepository segs, IhMeasurementRepository measurements, CosmicDoseRepository doses,
                           PregnancyDeclarationRepository pregnancies, Population population, ProgrammeCatalog catalog, EnrolmentEngine engine,
                           CaseService cases, EmployeeRecordAccess records, SurveillanceSupport support, AuditService audit) {
        this.segs = segs;
        this.measurements = measurements;
        this.doses = doses;
        this.pregnancies = pregnancies;
        this.population = population;
        this.catalog = catalog;
        this.engine = engine;
        this.cases = cases;
        this.records = records;
        this.support = support;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ SEGs

    public record SegView(String code, String name, String locationCode, List<String> jobCodes, String hazardCode, String unit,
                          BigDecimal actionLevel, BigDecimal limitValue, BigDecimal latestValue, LocalDate latestMeasuredOn,
                          boolean aboveActionLevel, List<String> enrolledProgrammes, boolean active) {
    }

    public record SegCommand(@NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9_\\-]{1,39}") String code, @NotBlank @Size(max = 200) String name,
                             @NotBlank @Pattern(regexp = "[A-Za-z0-9_\\-]{1,40}") String locationCode,
                             @Size(min = 1, max = 30) List<@Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String> jobCodes,
                             @NotBlank @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String hazardCode, @NotBlank @Size(max = 30) String unit,
                             @NotNull @DecimalMin("0") BigDecimal actionLevel, @NotNull @DecimalMin("0") BigDecimal limitValue, Boolean active) {
    }

    List<String> programmesFor(ExposureGroup g) {
        List<String> out = new ArrayList<>();
        for (Programme p : catalog.active()) {
            Eligibility.Criteria c = catalog.criteria(p);
            if (c.hazardCodes().contains(g.getHazardCode()) || c.exposureGroups().contains(g.getCode())) {
                out.add(p.getCode());
            }
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<SegView> segs(CurrentUser user) {
        List<SegView> out = new ArrayList<>();
        for (Population.SegState s : population.segStates().values()) {
            ExposureGroup g = s.seg();
            out.add(new SegView(g.getCode(), g.getName(), g.getLocationCode(), SurveillanceSupport.csv(g.getJobCodes()), g.getHazardCode(),
                    g.getUnit(), g.getActionLevel(), g.getLimitValue(), s.latest() == null ? null : s.latest().getValue(),
                    s.latest() == null ? null : s.latest().getMeasuredOn(), s.aboveActionLevel(), programmesFor(g), g.isActive()));
        }
        audit.record("EXPOSURE_GROUP_LIST", "EXPOSURE_GROUP", null, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    @Transactional
    public SegView upsertSeg(CurrentUser user, SegCommand cmd) {
        if (cmd.limitValue().compareTo(cmd.actionLevel()) < 0) {
            throw ApiException.badRequest("invalid_levels", "The limit value cannot be below the action level");
        }
        ExposureGroup g = segs.findById(cmd.code()).orElseGet(() -> {
            ExposureGroup n = new ExposureGroup();
            n.setCode(cmd.code());
            return n;
        });
        g.setName(cmd.name().strip());
        g.setLocationCode(cmd.locationCode());
        g.setJobCodes(SurveillanceSupport.joinCsv(cmd.jobCodes()));
        g.setHazardCode(cmd.hazardCode());
        g.setUnit(cmd.unit().strip());
        g.setActionLevel(cmd.actionLevel());
        g.setLimitValue(cmd.limitValue());
        g.setActive(cmd.active() == null || cmd.active());
        g.setUpdatedBy(user.username());
        g.setUpdatedAt(support.now());
        segs.save(g);
        audit.record("EXPOSURE_GROUP_UPSERT", "EXPOSURE_GROUP", g.getCode(), AuditService.Outcome.SUCCESS, null);
        return segs(user).stream().filter(v -> v.code().equals(g.getCode())).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ IH measurements

    public record MeasurementRow(@NotBlank @Pattern(regexp = "[A-Za-z0-9_\\-:./]{1,100}") String sourceRef,
                                 @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9_\\-]{1,39}") String segCode,
                                 @NotBlank @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String hazardCode,
                                 @NotBlank @Pattern(regexp = "NOISE_DOSIMETRY|AIR_SAMPLING|BIOLOGICAL|VIBRATION") String measurementType,
                                 @NotNull @DecimalMin("0") BigDecimal value, @NotBlank @Size(max = 30) String unit, @NotNull LocalDate measuredOn) {
    }

    public record ImportResult(int imported, int duplicates, int segsAboveActionLevel, int employeesEnrolled) {
    }

    public record MeasurementView(UUID id, String sourceRef, String segCode, String hazardCode, String measurementType, BigDecimal value,
                                  String unit, LocalDate measuredOn, boolean aboveActionLevel) {
    }

    @Transactional
    public ImportResult importMeasurements(String actor, List<MeasurementRow> rows) {
        if (rows == null || rows.isEmpty() || rows.size() > MAX_ROWS) {
            throw ApiException.badRequest("invalid_bulk", "Between 1 and " + MAX_ROWS + " measurements per call");
        }
        LocalDate today = support.today();
        int imported = 0;
        int duplicates = 0;
        Set<String> refs = new HashSet<>();
        Set<String> touched = new LinkedHashSet<>();
        Instant now = support.now();
        for (MeasurementRow r : rows) {
            ExposureGroup g = segs.findById(r.segCode())
                    .orElseThrow(() -> ApiException.badRequest("unknown_seg", "Unknown exposure group " + r.segCode()));
            if (!g.getHazardCode().equals(r.hazardCode())) {
                throw ApiException.badRequest("hazard_mismatch", "The hazard does not match exposure group " + r.segCode());
            }
            if (r.measuredOn().isAfter(today)) {
                throw ApiException.badRequest("invalid_date", "Measurement dates cannot be in the future");
            }
            if (!refs.add(r.sourceRef()) || measurements.existsBySourceRef(r.sourceRef())) {
                duplicates++;
                continue;
            }
            IhMeasurement m = new IhMeasurement();
            m.setId(UUID.randomUUID());
            m.setSourceRef(r.sourceRef());
            m.setSegCode(g.getCode());
            m.setHazardCode(r.hazardCode());
            m.setMeasurementType(r.measurementType());
            m.setValue(r.value());
            m.setUnit(r.unit().strip());
            m.setMeasuredOn(r.measuredOn());
            m.setImportedBy(actor);
            m.setImportedAt(now);
            measurements.save(m);
            touched.add(g.getCode());
            imported++;
        }
        measurements.flush();
        int above = 0;
        Set<String> enrolledPeople = new HashSet<>();
        Map<String, Population.SegState> states = population.segStates();
        for (String code : touched) {
            Population.SegState s = states.get(code);
            if (s == null || !s.aboveActionLevel()) {
                continue;
            }
            above++;
            ExposureGroup g = s.seg();
            List<String> jobs = SurveillanceSupport.csv(g.getJobCodes());
            List<Programme> programmes = catalog.active().stream().filter(p -> programmesFor(g).contains(p.getCode())).toList();
            for (Employee e : support.activeEmployees()) {
                if (e.getJobCode() == null || !jobs.contains(e.getJobCode()) || !g.getLocationCode().equals(e.getLocationCode())) {
                    continue;
                }
                for (Programme p : programmes) {
                    if (engine.enrol(e.getEmployeeId(), p, "EXPOSURE_GROUP")) {
                        enrolledPeople.add(e.getEmployeeId());
                    }
                }
            }
        }
        audit.recordAs(actor, "MODULE", "IH_MEASUREMENT_IMPORT", "EXPOSURE_GROUP", null, AuditService.Outcome.SUCCESS,
                "imported=" + imported + " duplicates=" + duplicates + " above=" + above + " enrolled=" + enrolledPeople.size());
        return new ImportResult(imported, duplicates, above, enrolledPeople.size());
    }

    @Transactional(readOnly = true)
    public List<MeasurementView> measurements(CurrentUser user, String segCode) {
        Map<String, Population.SegState> states = population.segStates();
        List<IhMeasurement> list = segCode == null ? measurements.findTop500ByOrderByMeasuredOnDesc()
                : measurements.findBySegCodeOrderByMeasuredOnDesc(segCode);
        List<MeasurementView> out = list.stream().limit(MAX_ROWS).map(m -> {
            Population.SegState s = states.get(m.getSegCode());
            return new MeasurementView(m.getId(), m.getSourceRef(), m.getSegCode(), m.getHazardCode(), m.getMeasurementType(), m.getValue(),
                    m.getUnit(), m.getMeasuredOn(), s != null && Population.above(m.getValue(), s.seg().getActionLevel()));
        }).toList();
        audit.record("IH_MEASUREMENT_LIST", "EXPOSURE_GROUP", segCode, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    // ------------------------------------------------------------------ cosmic dose

    public record DoseRow(@NotBlank @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String employeeId, @NotNull @Min(2000) @Max(2100) Integer year,
                          @NotNull @DecimalMin("0") BigDecimal doseMsv, @NotBlank @Size(max = 60) String source) {
    }

    public record DoseImportResult(int imported, int updated, int alerts, List<String> unknownEmployees) {
    }

    public record DoseView(String employeeId, String displayName, int year, BigDecimal doseMsv, boolean aboveThreshold, boolean pregnancyDeclared) {
    }

    BigDecimal threshold() {
        return support.configDecimal("surveillance.cosmic-dose-alert-msv", new BigDecimal("6.0"));
    }

    @Transactional
    public DoseImportResult importDoses(String actor, List<DoseRow> rows) {
        if (rows == null || rows.isEmpty() || rows.size() > MAX_ROWS) {
            throw ApiException.badRequest("invalid_bulk", "Between 1 and " + MAX_ROWS + " dose rows per call");
        }
        BigDecimal threshold = threshold();
        int imported = 0;
        int updated = 0;
        int alerts = 0;
        List<String> unknown = new ArrayList<>();
        Instant now = support.now();
        for (DoseRow r : rows) {
            Employee e = support.employee(r.employeeId()).orElse(null);
            if (e == null) {
                unknown.add(r.employeeId());
                continue;
            }
            if (r.doseMsv().compareTo(BigDecimal.valueOf(1000)) > 0) {
                throw ApiException.badRequest("invalid_dose", "Implausible dose value");
            }
            CosmicDose d = doses.findByEmployeeIdAndDoseYear(e.getEmployeeId(), r.year()).orElse(null);
            if (d == null) {
                d = new CosmicDose();
                d.setId(UUID.randomUUID());
                d.setEmployeeId(e.getEmployeeId());
                d.setDoseYear(r.year());
                imported++;
            } else {
                updated++;
            }
            d.setDoseMsv(r.doseMsv());
            d.setSource(r.source().strip());
            d.setImportedBy(actor);
            d.setImportedAt(now);
            if (r.doseMsv().compareTo(threshold) > 0 && !d.isAlerted()) {
                d.setAlerted(true);
                support.notifyRole(Roles.AME, "crew.dose-review", Map.of("employeeName", e.getDisplayName(), "level", "annual dose review"),
                        "/#/surveillance/exposure");
                alerts++;
            }
            doses.save(d);
        }
        audit.recordAs(actor, "MODULE", "COSMIC_DOSE_IMPORT", "COSMIC_DOSE", null, AuditService.Outcome.SUCCESS,
                "imported=" + imported + " updated=" + updated + " alerts=" + alerts + " unknown=" + unknown.size());
        return new DoseImportResult(imported, updated, alerts, unknown.stream().limit(100).toList());
    }

    @Transactional(readOnly = true)
    public List<DoseView> doses(CurrentUser user, Integer year, Boolean aboveThreshold) {
        BigDecimal threshold = threshold();
        List<CosmicDose> list = year == null ? doses.findAllByOrderByDoseYearDescEmployeeIdAsc() : doses.findByDoseYearOrderByEmployeeIdAsc(year);
        Set<String> pregnant = new HashSet<>();
        if (!list.isEmpty()) {
            pregnancies.findByEmployeeIdIn(list.stream().map(CosmicDose::getEmployeeId).distinct().toList())
                    .forEach(p -> pregnant.add(p.getEmployeeId()));
        }
        Map<String, Employee> emps = support.employeesById(list.stream().map(CosmicDose::getEmployeeId).distinct().toList());
        List<DoseView> out = new ArrayList<>();
        for (CosmicDose d : list) {
            boolean above = d.getDoseMsv().compareTo(threshold) > 0;
            if (aboveThreshold != null && aboveThreshold != above) {
                continue;
            }
            Employee e = emps.get(d.getEmployeeId());
            out.add(new DoseView(d.getEmployeeId(), e == null ? d.getEmployeeId() : e.getDisplayName(), d.getDoseYear(), d.getDoseMsv(), above,
                    pregnant.contains(d.getEmployeeId())));
            if (out.size() >= MAX_ROWS) {
                break;
            }
        }
        audit.record("COSMIC_DOSE_LIST", "COSMIC_DOSE", null, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    // ------------------------------------------------------------------ pregnancy declaration (crew)

    public record DeclarationView(UUID id, LocalDate declaredOn, String status, String caseRef) {
    }

    @Transactional
    public DeclarationView declarePregnancy(CurrentUser user, LocalDate declaredOn) {
        if (user.employeeId() == null || !user.hasRole(Roles.EMPLOYEE)) {
            throw ApiException.forbidden("Employees only");
        }
        Employee e = support.requireEmployee(user.employeeId());
        if (!support.isCrew(e)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "not_crew", "Declarations are recorded for flight and cabin crew");
        }
        LocalDate today = support.today();
        LocalDate on = declaredOn == null ? today : declaredOn;
        if (on.isAfter(today) || on.isBefore(today.minusDays(60))) {
            throw ApiException.badRequest("invalid_date", "The declaration date must be within the last 60 days");
        }
        PregnancyDeclaration p = new PregnancyDeclaration();
        p.setId(UUID.randomUUID());
        p.setEmployeeId(e.getEmployeeId());
        p.setDeclaredOn(on);
        p.setCreatedAt(support.now());
        OhCase c = cases.openIdempotent(OpenCaseCommand.builder(e.getEmployeeId(), CaseCategory.EXPOSURE, CaseSource.SURVEILLANCE_TRIGGER)
                .caseType(CaseType.EXPOSURE).sourceRef("surveillance:dose-review:" + p.getId()).build());
        p.setCaseId(c.getId());
        pregnancies.save(p);
        support.notifyRole(Roles.AME, "crew.dose-review", Map.of("employeeName", e.getDisplayName(), "level", "priority review"),
                "/#/surveillance/exposure");
        audit.record("CREW_DOSE_DECLARATION", "EMPLOYEE", e.getEmployeeId(), AuditService.Outcome.SUCCESS,
                "remaining dose limit " + PREGNANCY_DOSE_LIMIT_MSV + " mSv flagged");
        return new DeclarationView(p.getId(), on, "RECEIVED", c.getCaseRef());
    }

    // ------------------------------------------------------------------ personal exposure history (ODH)

    public record ExposureRow(String segCode, String hazardCode, LocalDate from, LocalDate to, BigDecimal latestValue, String unit) {
    }

    public record DoseYear(int year, BigDecimal doseMsv, boolean aboveThreshold) {
    }

    public record ExposureHistory(String employeeId, List<ExposureRow> exposures, List<DoseYear> cosmicDose, Boolean pregnancyDeclared,
                                  BigDecimal pregnancyDoseLimitMsv) {
    }

    @Transactional(readOnly = true)
    public ExposureHistory history(CurrentUser user, String employeeId) {
        Employee e = records.requireClinical(user, employeeId, DataClassification.CLINICAL, AccessPolicy.Action.READ, "EXPOSURE_HISTORY_READ");
        List<ExposureRow> rows = new ArrayList<>();
        for (ExposureGroup g : segs.findAllByOrderByCodeAsc()) {
            if (e.getJobCode() == null || !SurveillanceSupport.csv(g.getJobCodes()).contains(e.getJobCode())
                    || !g.getLocationCode().equals(e.getLocationCode())) {
                continue;
            }
            List<IhMeasurement> ms = measurements.findBySegCodeOrderByMeasuredOnDesc(g.getCode());
            LocalDate from = ms.stream().map(IhMeasurement::getMeasuredOn).min(Comparator.naturalOrder()).orElse(null);
            LocalDate to = ms.stream().map(IhMeasurement::getMeasuredOn).max(Comparator.naturalOrder()).orElse(null);
            rows.add(new ExposureRow(g.getCode(), g.getHazardCode(), from, g.isActive() ? null : to, ms.isEmpty() ? null : ms.get(0).getValue(),
                    g.getUnit()));
        }
        boolean aeromedical = user.hasAnyRole(Roles.AME, Roles.CMO);
        List<DoseYear> dose = List.of();
        Boolean pregnancy = null;
        BigDecimal limit = null;
        if (aeromedical) {
            BigDecimal threshold = threshold();
            dose = doses.findByEmployeeIdOrderByDoseYearDesc(employeeId).stream()
                    .map(d -> new DoseYear(d.getDoseYear(), d.getDoseMsv(), d.getDoseMsv().compareTo(threshold) > 0)).toList();
            pregnancy = !pregnancies.findByEmployeeIdOrderByDeclaredOnDesc(employeeId).isEmpty();
            limit = pregnancy ? PREGNANCY_DOSE_LIMIT_MSV : null;
        }
        audit.record("EXPOSURE_HISTORY_READ", "EMPLOYEE", employeeId, AuditService.Outcome.SUCCESS, "segs=" + rows.size());
        return new ExposureHistory(employeeId, rows, dose, pregnancy, limit);
    }
}
