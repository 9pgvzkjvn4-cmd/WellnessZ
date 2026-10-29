package com.wellnessz.masterdata.fitness;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUserProvider;

/**
 * E18 master data for increment 2: role fitness standards (versioned), the restriction catalogue and job-demand
 * profiles used by E05 modified-duty matching. Everything here is non-clinical reference data.
 */
@RestController
@RequestMapping("/api/v1/masterdata")
public class FitnessMasterDataController {

    /** Demand dimensions a job profile may declare (numeric limits or booleans). */
    public static final Set<String> NUMERIC_DIMENSIONS = Set.of("LIFT_KG", "PUSH_PULL_KG", "HOURS_PER_DAY", "SCREEN_HOURS");
    public static final Set<String> BOOLEAN_DIMENSIONS = Set.of("CLIMB", "AIRSIDE_DRIVE", "NIGHT", "KNIFE", "CHEMICAL", "RESPIRATOR",
            "NOISE", "HEAT", "HEIGHT", "FLIGHT_DUTY", "FOOD_HANDLING", "SAFETY_CRITICAL");
    private static final String CODE = "[A-Z0-9_]{1,40}";

    private final FitnessStandardRepository standards;
    private final RestrictionRepository restrictions;
    private final JobDemandProfileRepository demands;
    private final CurrentUserProvider users;
    private final AuditService audit;
    private final JsonMapper json;
    private final Clock clock;

    public FitnessMasterDataController(FitnessStandardRepository standards, RestrictionRepository restrictions,
                                       JobDemandProfileRepository demands, CurrentUserProvider users, AuditService audit,
                                       JsonMapper json, Clock clock) {
        this.standards = standards;
        this.restrictions = restrictions;
        this.demands = demands;
        this.users = users;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ fitness standards

    public record StandardView(String code, int version, String title, String roleCategory, String source, String referenceUrl,
                               int validityMonths, Integer validityMonthsOver40, String requiresSignoffRole, List<String> limitationCodes,
                               String status, String updatedBy, java.time.Instant updatedAt) {
        public static StandardView of(FitnessStandard f) {
            return new StandardView(f.getCode(), f.getVersion(), f.getTitle(), f.getRoleCategory(), f.getSource(), f.getReferenceUrl(),
                    f.getValidityMonths(), f.getValidityMonthsOver40(), f.getRequiresSignoffRole(), f.limitationCodeList(), f.getStatus(),
                    f.getUpdatedBy(), f.getUpdatedAt());
        }
    }

    public record StandardRequest(@NotBlank @Size(max = 200) String title, @NotBlank @Pattern(regexp = CODE) String roleCategory,
                                  @NotBlank @Size(max = 300) String source, @Size(max = 500) @Pattern(regexp = "https://.*") String referenceUrl,
                                  @Min(1) @Max(120) int validityMonths, @Min(1) @Max(120) Integer validityMonthsOver40,
                                  @Pattern(regexp = "AME|CMO") String requiresSignoffRole,
                                  @Size(max = 40) List<@Pattern(regexp = "[A-Z]{2,4}") String> limitationCodes) {
    }

    @GetMapping("/fitness-standards")
    public List<StandardView> fitnessStandards() {
        return standards.findByStatusOrderByCodeAsc("ACTIVE").stream().map(StandardView::of).toList();
    }

    @PutMapping("/fitness-standards/{code}")
    @PreAuthorize("hasAnyRole('OH_ADMIN','CMO')")
    @Transactional
    public StandardView putStandard(@PathVariable @Pattern(regexp = CODE) String code, @Valid @RequestBody StandardRequest r) {
        int version = standards.findFirstByCodeOrderByVersionDesc(code).map(prev -> {
            prev.setStatus("SUPERSEDED");
            return prev.getVersion() + 1;
        }).orElse(1);
        FitnessStandard f = new FitnessStandard(code, version);
        f.setTitle(r.title());
        f.setRoleCategory(r.roleCategory());
        f.setSource(r.source());
        f.setReferenceUrl(r.referenceUrl());
        f.setValidityMonths(r.validityMonths());
        f.setValidityMonthsOver40(r.validityMonthsOver40());
        f.setRequiresSignoffRole(r.requiresSignoffRole());
        f.setLimitationCodes(r.limitationCodes() == null ? null : String.join(",", r.limitationCodes()));
        f.setStatus("ACTIVE");
        f.setUpdatedBy(users.require().username());
        f.setUpdatedAt(clock.instant());
        standards.save(f);
        audit.record("FITNESS_STANDARD_VERSION", "FITNESS_STANDARD", code, AuditService.Outcome.SUCCESS, "version=" + version);
        return StandardView.of(f);
    }

    // ------------------------------------------------------------------ restriction catalogue

    public record RestrictionView(String code, String label, String dimension, boolean valueRequired, String unit, boolean active) {
        public static RestrictionView of(Restriction r) {
            return new RestrictionView(r.getCode(), r.getLabel(), r.getDimension(), r.isValueRequired(), r.getUnit(), r.isActive());
        }
    }

    public record RestrictionRequest(@NotBlank @Size(max = 200) String label, @NotBlank @Pattern(regexp = CODE) String dimension,
                                     boolean valueRequired, @Size(max = 20) String unit, Boolean active) {
    }

    @GetMapping("/restrictions")
    public List<RestrictionView> restrictions() {
        return restrictions.findAllByOrderByCodeAsc().stream().map(RestrictionView::of).toList();
    }

    @PutMapping("/restrictions/{code}")
    @PreAuthorize("hasAnyRole('OH_ADMIN','NURSING_HEAD')")
    @Transactional
    public RestrictionView putRestriction(@PathVariable @Pattern(regexp = CODE) String code, @Valid @RequestBody RestrictionRequest r) {
        if (!NUMERIC_DIMENSIONS.contains(r.dimension()) && !BOOLEAN_DIMENSIONS.contains(r.dimension())) {
            throw ApiException.badRequest("unknown_dimension", "Unknown demand dimension");
        }
        Restriction x = restrictions.findById(code).orElseGet(() -> new Restriction(code));
        x.setLabel(r.label());
        x.setDimension(r.dimension());
        x.setValueRequired(r.valueRequired());
        x.setUnit(r.unit());
        x.setActive(r.active() == null || r.active());
        x.setUpdatedBy(users.require().username());
        x.setUpdatedAt(clock.instant());
        restrictions.save(x);
        audit.record("RESTRICTION_CATALOGUE_UPDATE", "RESTRICTION", code, AuditService.Outcome.SUCCESS, null);
        return RestrictionView.of(x);
    }

    // ------------------------------------------------------------------ job demand profiles

    public record DemandView(String jobCode, String title, String unit, Map<String, Object> demands, String sitting, String validatedBy,
                             java.time.Instant validatedAt) {
    }

    public record DemandRequest(@NotBlank @Pattern(regexp = "[A-Z0-9_\\-]{1,64}") String jobCode, @NotBlank @Size(max = 200) String title,
                                @NotBlank @Pattern(regexp = "[A-Z]{2,5}") String unit, @NotNull Map<String, Object> demands,
                                @Pattern(regexp = "LOW|MEDIUM|HIGH") String sitting, @Size(max = 200) String validatedBy) {
    }

    @GetMapping("/job-demands")
    @PreAuthorize("hasAnyRole('OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD','CASE_MANAGER','PSYCH_CLINICIAN','OH_ADMIN','MANAGER','HR')")
    public List<DemandView> jobDemands(@RequestParam(required = false) @Pattern(regexp = "[A-Z]{2,5}") String unit) {
        List<JobDemandProfile> list = unit == null ? demands.findAllByOrderByUnitAscJobCodeAsc() : demands.findByUnitOrderByJobCodeAsc(unit);
        return list.stream().map(this::view).toList();
    }

    @PutMapping("/job-demands/{jobCode}")
    @PreAuthorize("hasAnyRole('OH_ADMIN','NURSING_HEAD')")
    @Transactional
    public DemandView putJobDemand(@PathVariable @Pattern(regexp = "[A-Z0-9_\\-]{1,64}") String jobCode, @Valid @RequestBody DemandRequest r) {
        if (!jobCode.equals(r.jobCode())) {
            throw ApiException.badRequest("job_code_mismatch", "Path and body job codes differ");
        }
        DemandView v = upsert(r);
        audit.record("JOB_DEMAND_UPDATE", "JOB_DEMAND", jobCode, AuditService.Outcome.SUCCESS, null);
        return v;
    }

    public record ImportResult(int upserted) {
    }

    @PostMapping("/job-demands/import")
    @PreAuthorize("hasRole('OH_ADMIN')")
    @Transactional
    public ImportResult importJobDemands(@RequestBody @Size(max = 2000) List<@Valid DemandRequest> items) {
        items.forEach(this::upsert);
        audit.record("JOB_DEMAND_IMPORT", "JOB_DEMAND", null, AuditService.Outcome.SUCCESS, "profiles=" + items.size());
        return new ImportResult(items.size());
    }

    private DemandView upsert(DemandRequest r) {
        Map<String, Object> clean = validateDemands(r.demands());
        JobDemandProfile p = demands.findById(r.jobCode()).orElseGet(() -> new JobDemandProfile(r.jobCode()));
        p.setTitle(r.title());
        p.setUnit(r.unit());
        p.setDemands(json.writeValueAsString(clean));
        p.setSitting(r.sitting());
        if (r.validatedBy() != null) {
            p.setValidatedBy(r.validatedBy());
            p.setValidatedAt(clock.instant());
        }
        p.setUpdatedBy(users.require().username());
        p.setUpdatedAt(clock.instant());
        demands.save(p);
        return view(p);
    }

    static Map<String, Object> validateDemands(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : in.entrySet()) {
            if (NUMERIC_DIMENSIONS.contains(e.getKey()) && e.getValue() instanceof Number n && n.doubleValue() >= 0 && n.doubleValue() <= 500) {
                out.put(e.getKey(), n);
            } else if (BOOLEAN_DIMENSIONS.contains(e.getKey()) && e.getValue() instanceof Boolean b) {
                out.put(e.getKey(), b);
            } else {
                throw ApiException.badRequest("invalid_demand", "Invalid demand " + e.getKey()
                        + ": numeric 0-500 for " + String.join(",", NUMERIC_DIMENSIONS) + "; boolean for the other dimensions");
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public DemandView view(JobDemandProfile p) {
        return new DemandView(p.getJobCode(), p.getTitle(), p.getUnit(), json.readValue(p.getDemands(), LinkedHashMap.class), p.getSitting(),
                p.getValidatedBy(), p.getValidatedAt());
    }
}
