package com.wellnessz.e07.programme;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.e07.model.Enrolment;
import com.wellnessz.e07.model.EnrolmentRepository;
import com.wellnessz.e07.model.Programme;
import com.wellnessz.e07.model.ProgrammeRepository;
import com.wellnessz.e07.support.SurveillanceSupport;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Programme & protocol builder (E07-F01): versioned programmes (DRAFT → ACTIVE → RETIRED), population simulation without
 * writes, activation with enrolment recalculation.
 */
@Service
public class ProgrammeService {

    static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,39}");
    static final Pattern TEST = Pattern.compile(
            "AUDIOMETRY|SPIROMETRY|VISION|ECG|QUESTIONNAIRE|PHYSICAL_EXAM|LAB:[A-Za-z0-9.\\-]{1,20}|BIOMONITORING:[A-Za-z0-9.\\-_]{1,20}"
                    + "|IMMUNISATION:[A-Z0-9_]{1,30}");
    static final Pattern LIST_CODE = Pattern.compile("[A-Za-z0-9_\\-]{1,64}");
    static final Set<String> RESPONSIBLE_ROLES = Set.of(Roles.OH_CLINICIAN, Roles.OH_PHYSICIAN, Roles.AME, Roles.CMO, Roles.NURSING_HEAD);

    private final ProgrammeRepository programmes;
    private final ProgrammeCatalog catalog;
    private final Population population;
    private final EnrolmentEngine engine;
    private final EnrolmentRepository enrolments;
    private final SurveillanceSupport support;
    private final AuditService audit;

    public ProgrammeService(ProgrammeRepository programmes, ProgrammeCatalog catalog, Population population, EnrolmentEngine engine,
                            EnrolmentRepository enrolments, SurveillanceSupport support, AuditService audit) {
        this.programmes = programmes;
        this.catalog = catalog;
        this.population = population;
        this.engine = engine;
        this.enrolments = enrolments;
        this.support = support;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ API types

    public record EligibilityInput(@Size(max = 50) List<String> jobCodes, @Size(max = 50) List<String> departmentCodes,
                                   @Size(max = 50) List<String> subsidiaryCodes, @Size(max = 50) List<String> hazardCodes,
                                   @Size(max = 50) List<String> exposureGroups, @Size(max = 20) List<String> licenceTypes,
                                   @Min(16) @Max(80) Integer minAge) {
    }

    public record ProgrammeCommand(@NotBlank @Size(max = 40) String code, @NotBlank @Size(max = 200) String title,
                                   @NotNull @Valid EligibilityInput eligibility,
                                   @NotEmpty @Size(max = 20) @Valid List<ProgrammeCatalog.PanelItem> panel,
                                   @NotNull @Min(1) @Max(120) Integer frequencyMonths, @Min(1) @Max(120) Integer frequencyMonthsOver40,
                                   @Size(max = 40) String standardCode, @NotBlank @Size(max = 40) String responsibleRole,
                                   @NotBlank @Size(max = 40) String serviceCode, @Size(max = 500) String regulatoryBasis,
                                   LocalDate effectiveFrom) {
    }

    public record VersionView(int version, String status, LocalDate effectiveFrom, String approvedBy, Instant approvedAt) {
    }

    public record ProgrammeView(String code, int version, String title, String status, LocalDate effectiveFrom,
                                Eligibility.Criteria eligibility, List<ProgrammeCatalog.PanelItem> panel, int frequencyMonths,
                                Integer frequencyMonthsOver40, String standardCode, String responsibleRole, String serviceCode,
                                String regulatoryBasis, Integer eligibleHeadcount, List<VersionView> versions) {
    }

    public record UnitCount(String subsidiaryCode, String departmentCode, int count) {
    }

    public record Simulation(String code, int version, int eligibleHeadcount, List<UnitCount> byUnit, int newlyEnrolled, int wouldBeOverdue) {
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<ProgrammeView> list(CurrentUser user) {
        Map<String, Programme> current = new LinkedHashMap<>();
        for (Programme p : programmes.findAllByOrderByCodeAscVersionDesc()) {
            Programme seen = current.get(p.getCode());
            if (seen == null || !"ACTIVE".equals(seen.getStatus()) && "ACTIVE".equals(p.getStatus())) {
                current.put(p.getCode(), p);
            }
        }
        Population.Snapshot snap = population.snapshot();
        LocalDate today = support.today();
        List<ProgrammeView> out = current.values().stream().map(p -> view(p, headcount(p, snap, today), null)).toList();
        audit.record("SURVEILLANCE_PROGRAMME_LIST", "PROGRAMME", null, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    @Transactional(readOnly = true)
    public ProgrammeView get(CurrentUser user, String code) {
        List<Programme> versions = programmes.findByCodeOrderByVersionDesc(code);
        if (versions.isEmpty()) {
            throw ApiException.notFound("Programme");
        }
        Programme current = versions.stream().filter(p -> "ACTIVE".equals(p.getStatus())).findFirst().orElse(versions.get(0));
        List<VersionView> vs = versions.stream()
                .map(p -> new VersionView(p.getVersion(), p.getStatus(), p.getEffectiveFrom(), p.getApprovedBy(), p.getApprovedAt())).toList();
        return view(current, headcount(current, population.snapshot(), support.today()), vs);
    }

    int headcount(Programme p, Population.Snapshot snap, LocalDate today) {
        Eligibility.Criteria c = catalog.criteria(p);
        return (int) snap.persons().values().stream().filter(x -> Eligibility.match(c, x, today).isPresent()).count();
    }

    ProgrammeView view(Programme p, Integer headcount, List<VersionView> versions) {
        return new ProgrammeView(p.getCode(), p.getVersion(), p.getTitle(), p.getStatus(), p.getEffectiveFrom(), catalog.criteria(p),
                catalog.panel(p), p.getFrequencyMonths(), p.getFrequencyMonthsOver40(), p.getStandardCode(), p.getResponsibleRole(),
                p.getServiceCode(), p.getRegulatoryBasis(), headcount, versions);
    }

    // ------------------------------------------------------------------ builder

    @Transactional
    public ProgrammeView create(CurrentUser user, ProgrammeCommand cmd) {
        validate(cmd);
        if (!programmes.findByCodeOrderByVersionDesc(cmd.code()).isEmpty()) {
            throw ApiException.conflict("programme_exists", "A programme with this code already exists – edit it to create a new version");
        }
        Programme p = newVersion(user, cmd, 1);
        programmes.save(p);
        audit.record("SURVEILLANCE_PROGRAMME_CREATE", "PROGRAMME", p.getCode(), AuditService.Outcome.SUCCESS, "version=1");
        return view(p, null, null);
    }

    @Transactional
    public ProgrammeView update(CurrentUser user, String code, ProgrammeCommand cmd) {
        if (!code.equals(cmd.code())) {
            throw ApiException.badRequest("code_mismatch", "The programme code cannot be changed");
        }
        validate(cmd);
        List<Programme> versions = programmes.findByCodeOrderByVersionDesc(code);
        if (versions.isEmpty()) {
            throw ApiException.notFound("Programme");
        }
        Programme latest = versions.get(0);
        Programme p;
        if ("DRAFT".equals(latest.getStatus())) {
            p = latest;
            apply(p, cmd);
            p.setUpdatedAt(support.now());
        } else {
            p = newVersion(user, cmd, latest.getVersion() + 1);
            programmes.save(p);
        }
        audit.record("SURVEILLANCE_PROGRAMME_UPDATE", "PROGRAMME", code, AuditService.Outcome.SUCCESS, "version=" + p.getVersion());
        return view(p, null, null);
    }

    @Transactional(readOnly = true)
    public Simulation simulate(CurrentUser user, String code, Integer version) {
        Programme p = version != null ? programmes.findByCodeAndVersion(code, version).orElseThrow(() -> ApiException.notFound("Programme version"))
                : programmes.findByCodeOrderByVersionDesc(code).stream().findFirst().orElseThrow(() -> ApiException.notFound("Programme"));
        Map<String, Enrolment> current = new LinkedHashMap<>();
        enrolments.findByProgrammeCode(code).stream().filter(Enrolment::isActive).forEach(e -> current.put(e.getEmployeeId(), e));
        Simulation sim = compute(code, p.getVersion(), catalog.criteria(p), p.getFrequencyMonths(), p.getFrequencyMonthsOver40(),
                population.snapshot(), current, engine.graceDays(), support.today());
        audit.record("SURVEILLANCE_PROGRAMME_SIMULATE", "PROGRAMME", code, AuditService.Outcome.SUCCESS, "version=" + p.getVersion());
        return sim;
    }

    /** Population impact of a programme version (pure: no writes). */
    static Simulation compute(String code, int version, Eligibility.Criteria c, int frequency, Integer frequencyOver40, Population.Snapshot snap,
                              Map<String, Enrolment> current, int grace, LocalDate today) {
        Map<String, Integer> units = new TreeMap<>();
        int eligible = 0;
        int newly = 0;
        int overdue = 0;
        for (Eligibility.Person person : snap.persons().values()) {
            if (Eligibility.match(c, person, today).isEmpty()) {
                continue;
            }
            eligible++;
            Employee e = snap.employees().get(person.employeeId());
            units.merge(e.getSubsidiaryCode() + "|" + e.getDepartmentCode(), 1, Integer::sum);
            Enrolment existing = current.get(person.employeeId());
            if (existing == null) {
                newly++;
            }
            LocalDate next = Eligibility.nextDue(existing == null ? null : existing.getLastCompletedOn(),
                    existing == null ? today : existing.getEnrolledOn(), frequency, frequencyOver40, person.dateOfBirth(), grace);
            if (next.isBefore(today)) {
                overdue++;
            }
        }
        List<UnitCount> byUnit = new ArrayList<>();
        units.forEach((k, v) -> {
            String[] parts = k.split("\\|", 2);
            byUnit.add(new UnitCount(parts[0], parts[1], v));
        });
        byUnit.sort(Comparator.comparing(UnitCount::count).reversed().thenComparing(UnitCount::subsidiaryCode));
        return new Simulation(code, version, eligible, byUnit, newly, overdue);
    }

    @Transactional
    public ProgrammeView activate(CurrentUser user, String code, LocalDate effectiveFrom) {
        List<Programme> versions = programmes.findByCodeOrderByVersionDesc(code);
        if (versions.isEmpty()) {
            throw ApiException.notFound("Programme");
        }
        Programme draft = versions.stream().filter(p -> "DRAFT".equals(p.getStatus())).findFirst()
                .orElseThrow(() -> ApiException.conflict("no_draft", "There is no draft version to activate"));
        Instant now = support.now();
        versions.stream().filter(p -> "ACTIVE".equals(p.getStatus())).forEach(p -> {
            p.setStatus("RETIRED");
            p.setUpdatedAt(now);
        });
        programmes.flush();
        draft.setStatus("ACTIVE");
        draft.setEffectiveFrom(effectiveFrom != null ? effectiveFrom : draft.getEffectiveFrom() != null ? draft.getEffectiveFrom() : support.today());
        draft.setApprovedBy(user.username());
        draft.setApprovedAt(now);
        draft.setUpdatedAt(now);
        programmes.flush();
        EnrolmentEngine.RecalcResult r = engine.recalculate(List.of(code));
        audit.record("SURVEILLANCE_PROGRAMME_ACTIVATE", "PROGRAMME", code, AuditService.Outcome.SUCCESS,
                "version=" + draft.getVersion() + " enrolled=" + r.enrolled() + " removed=" + r.removed());
        return view(draft, null, null);
    }

    @Transactional
    public ProgrammeView retire(CurrentUser user, String code) {
        Programme active = catalog.active(code).orElseThrow(() -> ApiException.conflict("not_active", "The programme has no active version"));
        active.setStatus("RETIRED");
        active.setUpdatedAt(support.now());
        programmes.flush();
        EnrolmentEngine.RecalcResult r = engine.recalculate(List.of(code));
        audit.record("SURVEILLANCE_PROGRAMME_RETIRE", "PROGRAMME", code, AuditService.Outcome.SUCCESS, "removed=" + r.removed());
        return view(active, null, null);
    }

    // ------------------------------------------------------------------ helpers

    static void validate(ProgrammeCommand cmd) {
        if (!CODE.matcher(cmd.code()).matches()) {
            throw ApiException.badRequest("invalid_code", "Programme codes are upper-case letters, digits and underscores");
        }
        for (ProgrammeCatalog.PanelItem i : cmd.panel()) {
            if (i == null || i.test() == null || !TEST.matcher(i.test()).matches()) {
                throw ApiException.badRequest("invalid_panel_test", "Unknown panel test");
            }
            if (i.standard() != null && !LIST_CODE.matcher(i.standard()).matches()) {
                throw ApiException.badRequest("invalid_standard", "Invalid standard code");
            }
        }
        EligibilityInput e = cmd.eligibility();
        for (List<String> list : java.util.Arrays.asList(e.jobCodes(), e.departmentCodes(), e.subsidiaryCodes(), e.hazardCodes(),
                e.exposureGroups(), e.licenceTypes())) {
            if (list != null && list.stream().anyMatch(v -> v == null || !LIST_CODE.matcher(v).matches())) {
                throw ApiException.badRequest("invalid_eligibility", "Eligibility lists contain codes only");
            }
        }
        Eligibility.Criteria c = criteria(e);
        if (c.jobCodes().isEmpty() && c.hazardCodes().isEmpty() && c.exposureGroups().isEmpty() && c.licenceTypes().isEmpty()
                && c.departmentCodes().isEmpty() && c.subsidiaryCodes().isEmpty()) {
            throw ApiException.badRequest("eligibility_required", "Define at least one eligibility criterion");
        }
        if (!RESPONSIBLE_ROLES.contains(cmd.responsibleRole())) {
            throw ApiException.badRequest("invalid_responsible_role", "Responsible role must be an OH clinical role");
        }
        if (!LIST_CODE.matcher(cmd.serviceCode()).matches() || cmd.standardCode() != null && !LIST_CODE.matcher(cmd.standardCode()).matches()) {
            throw ApiException.badRequest("invalid_code", "Invalid service or standard code");
        }
        if (cmd.frequencyMonthsOver40() != null && cmd.frequencyMonthsOver40() > cmd.frequencyMonths()) {
            throw ApiException.badRequest("invalid_frequency", "The over-40 frequency cannot be longer than the standard frequency");
        }
    }

    static Eligibility.Criteria criteria(EligibilityInput e) {
        return new Eligibility.Criteria(e.jobCodes(), e.departmentCodes(), e.subsidiaryCodes(), e.hazardCodes(), e.exposureGroups(),
                e.licenceTypes(), e.minAge());
    }

    private Programme newVersion(CurrentUser user, ProgrammeCommand cmd, int version) {
        Programme p = new Programme();
        p.setId(UUID.randomUUID());
        p.setCode(cmd.code());
        p.setVersion(version);
        p.setStatus("DRAFT");
        p.setCreatedBy(user.username());
        p.setCreatedAt(support.now());
        p.setUpdatedAt(support.now());
        apply(p, cmd);
        return p;
    }

    private void apply(Programme p, ProgrammeCommand cmd) {
        p.setTitle(cmd.title().strip());
        p.setEligibility(support.write(criteria(cmd.eligibility())));
        p.setPanel(support.write(cmd.panel()));
        p.setFrequencyMonths(cmd.frequencyMonths());
        p.setFrequencyMonthsOver40(cmd.frequencyMonthsOver40());
        p.setStandardCode(cmd.standardCode());
        p.setResponsibleRole(cmd.responsibleRole());
        p.setServiceCode(cmd.serviceCode());
        p.setRegulatoryBasis(cmd.regulatoryBasis());
        p.setEffectiveFrom(cmd.effectiveFrom());
    }

    public Optional<Programme> activeProgramme(String code) {
        return catalog.active(code);
    }
}
