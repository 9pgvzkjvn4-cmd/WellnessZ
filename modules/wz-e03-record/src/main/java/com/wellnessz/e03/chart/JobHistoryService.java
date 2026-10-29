package com.wellnessz.e03.chart;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.e03.model.ClinicalEncounter;
import com.wellnessz.e03.model.ClinicalEncounterRepository;
import com.wellnessz.e03.model.JobEntry;
import com.wellnessz.e03.model.JobEntryRepository;
import com.wellnessz.e03.model.Result;
import com.wellnessz.e03.model.ResultRepository;
import com.wellnessz.e03.support.JsonSupport;
import com.wellnessz.e03.support.RecordGuard;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.masterdata.Hazard;
import com.wellnessz.masterdata.HazardRepository;
import com.wellnessz.masterdata.JobCode;
import com.wellnessz.masterdata.JobCodeRepository;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * ODH job and exposure history (E03-BL-003) and exposure-driven surveillance due dates. The current job is synchronised
 * from the HRIS employee master on read (a job-code change closes the previous HRIS entry); exposures come from the job
 * catalogue hazards; clinicians add earlier jobs and corrections manually.
 */
@Service
public class JobHistoryService {

    /** Surveillance programme per hazard: title + the evidence that counts as "done" (device category / encounter type). */
    record Programme(String title, String resultCategory, String encounterType) {
    }

    static final Map<String, Programme> PROGRAMMES = Map.of(
            "NOISE", new Programme("Audiometry (hearing conservation)", "AUDIOMETRY", "SURVEILLANCE_EXAM"),
            "JET_FUEL", new Programme("Chemical exposure surveillance (spirometry)", "SPIROMETRY", "SURVEILLANCE_EXAM"),
            "COSMIC_RAD", new Programme("Cosmic radiation dose review", null, "SURVEILLANCE_EXAM"),
            "FOOD_HYGIENE", new Programme("Food-handler health review", null, "FOOD_HANDLER"));

    private final JobEntryRepository jobs;
    private final JobCodeRepository jobCodes;
    private final HazardRepository hazards;
    private final ClinicalEncounterRepository encounters;
    private final ResultRepository results;
    private final RecordGuard guard;
    private final JsonSupport json;
    private final AuditService audit;
    private final Clock clock;

    public JobHistoryService(JobEntryRepository jobs, JobCodeRepository jobCodes, HazardRepository hazards, ClinicalEncounterRepository encounters,
                             ResultRepository results, RecordGuard guard, JsonSupport json, AuditService audit, Clock clock) {
        this.jobs = jobs;
        this.jobCodes = jobCodes;
        this.hazards = hazards;
        this.encounters = encounters;
        this.results = results;
        this.guard = guard;
        this.json = json;
        this.audit = audit;
        this.clock = clock;
    }

    public record HazardView(String code, String name, String category) {
    }

    public record JobView(UUID id, String employeeId, String jobCode, String jobTitle, String employer, String industry,
                          LocalDate startDate, LocalDate endDate, boolean current, String source, List<HazardView> hazards, String duties,
                          Integer weeklyHours) {
    }

    public record JobRequest(@Pattern(regexp = "[A-Z0-9_]{1,64}") String jobCode, @NotBlank @Size(max = 200) String jobTitle,
                             @Size(max = 200) String employer, @Size(max = 200) String industry, LocalDate startDate, LocalDate endDate,
                             Boolean current, @Size(max = 1000) String duties, @Min(0) @Max(100) Integer weeklyHours,
                             @Size(max = 20) List<@Pattern(regexp = "[A-Z0-9_]{1,64}") String> hazardCodes) {
    }

    public record SurveillanceDue(String hazardCode, String programme, Integer intervalMonths, LocalDate lastDone, LocalDate dueDate,
                                  boolean overdue) {
    }

    // ------------------------------------------------------------------ reads

    @Transactional
    public List<JobView> list(CurrentUser user, String employeeId) {
        RecordGuard.Access a = guard.read(user, employeeId, "CHART_JOBS_READ");
        List<JobView> out = views(sync(a.employee()));
        audit.record("CHART_JOBS_READ", "EMPLOYEE", employeeId, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    /** Synchronises the HRIS current job and returns all entries, current first then by start date. */
    @Transactional
    public List<JobEntry> sync(Employee e) {
        List<JobEntry> all = new ArrayList<>(jobs.findByEmployeeIdOrderByRecordedAtDesc(e.getEmployeeId()));
        JobEntry hrisCurrent = all.stream().filter(j -> "HRIS".equals(j.getSource()) && j.isCurrent()).findFirst().orElse(null);
        String code = e.getJobCode();
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (code != null && (hrisCurrent == null || !code.equals(hrisCurrent.getJobCode()))) {
            boolean firstSync = all.stream().noneMatch(j -> "HRIS".equals(j.getSource()));
            if (hrisCurrent != null) {
                hrisCurrent.setCurrent(false);
                hrisCurrent.setEndDate(today);
            }
            JobCode jc = jobCodes.findById(code).orElse(null);
            JobEntry j = new JobEntry();
            j.setId(UUID.randomUUID());
            j.setEmployeeId(e.getEmployeeId());
            j.setJobCode(code);
            j.setJobTitle(jc == null ? code : jc.getTitle());
            j.setEmployer("Group / " + e.getSubsidiaryCode());
            j.setIndustry(industry(e.getSubsidiaryCode()));
            j.setStartDate(firstSync ? null : today); // start of the first HRIS job is unknown to the OH record
            j.setCurrent(true);
            j.setSource("HRIS");
            j.setRecordedBy("hris-sync");
            j.setRecordedAt(clock.instant());
            jobs.save(j);
            all.add(0, j);
        }
        all.sort(Comparator.comparing(JobEntry::isCurrent).reversed()
                .thenComparing(JobEntry::getStartDate, Comparator.nullsFirst(Comparator.reverseOrder())));
        return all;
    }

    List<JobView> views(List<JobEntry> entries) {
        Map<String, Hazard> catalogue = hazardCatalogue();
        return entries.stream().map(j -> new JobView(j.getId(), j.getEmployeeId(), j.getJobCode(), j.getJobTitle(), j.getEmployer(),
                j.getIndustry(), j.getStartDate(), j.getEndDate(), j.isCurrent(), j.getSource(),
                hazardCodes(j).stream().map(catalogue::get).filter(Objects::nonNull)
                        .map(h -> new HazardView(h.getCode(), h.getName(), h.getCategory())).toList(),
                j.getDuties(), j.getWeeklyHours())).toList();
    }

    /** Exposures of a job: the job catalogue hazards for its job code plus manually recorded hazards. */
    Set<String> hazardCodes(JobEntry j) {
        Set<String> codes = new LinkedHashSet<>();
        if (j.getJobCode() != null) {
            jobCodes.findById(j.getJobCode()).map(JobCode::getHazardCodes).ifPresent(csv -> codes.addAll(csv(csv)));
        }
        codes.addAll(csv(j.getHazardCodes()));
        return codes;
    }

    private static List<String> csv(String csv) {
        return csv == null || csv.isBlank() ? List.of() : Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private Map<String, Hazard> hazardCatalogue() {
        Map<String, Hazard> m = new LinkedHashMap<>();
        hazards.findAll().forEach(h -> m.put(h.getCode(), h));
        return m;
    }

    static String industry(String subsidiary) {
        if (subsidiary == null) {
            return null;
        }
        return switch (subsidiary) {
            case "FLT", "CAB", "GND" -> "Air transportation";
            case "CGO", "DIST" -> "Freight transport and logistics";
            case "CAT" -> "Airline catering";
            case "DF" -> "Retail (duty free)";
            case "ENG" -> "Aircraft maintenance";
            case "SEC" -> "Security, fire and rescue services";
            case "HOSP" -> "Hospitality";
            case "TRN" -> "Training";
            default -> "Corporate services";
        };
    }

    // ------------------------------------------------------------------ writes

    @Transactional
    public JobView add(CurrentUser user, String employeeId, JobRequest r) {
        guard.write(user, employeeId, DataClassification.CLINICAL, AccessPolicy.Action.CREATE, "CHART_JOB_CREATE");
        JobEntry j = new JobEntry();
        j.setId(UUID.randomUUID());
        j.setEmployeeId(employeeId);
        j.setSource("MANUAL");
        j.setRecordedBy(user.username());
        j.setRecordedAt(clock.instant());
        apply(j, r);
        jobs.save(j);
        audit.record("CHART_JOB_CREATE", "JOB_HISTORY", j.getId().toString(), AuditService.Outcome.SUCCESS, null);
        return views(List.of(j)).get(0);
    }

    @Transactional
    public JobView update(CurrentUser user, UUID id, JobRequest r) {
        JobEntry j = jobs.findById(id).orElseThrow(() -> ApiException.notFound("Job"));
        guard.write(user, j.getEmployeeId(), DataClassification.CLINICAL, AccessPolicy.Action.UPDATE, "CHART_JOB_UPDATE");
        if ("HRIS".equals(j.getSource())) {
            // HRIS-owned facts (job code / title) stay authoritative; clinicians may add duties, hours, exposures and dates
            j.setDuties(r.duties());
            j.setWeeklyHours(r.weeklyHours());
            j.setHazardCodes(r.hazardCodes() == null ? null : String.join(",", r.hazardCodes()));
            if (r.startDate() != null) {
                j.setStartDate(r.startDate());
            }
        } else {
            apply(j, r);
        }
        audit.record("CHART_JOB_UPDATE", "JOB_HISTORY", id.toString(), AuditService.Outcome.SUCCESS, null);
        return views(List.of(j)).get(0);
    }

    private void apply(JobEntry j, JobRequest r) {
        if (r.endDate() != null && r.startDate() != null && r.endDate().isBefore(r.startDate())) {
            throw ApiException.badRequest("invalid_dates", "endDate must not be before startDate");
        }
        if (r.hazardCodes() != null) {
            Set<String> known = hazardCatalogue().keySet();
            if (!known.containsAll(r.hazardCodes())) {
                throw ApiException.badRequest("unknown_hazard", "Unknown hazard code");
            }
        }
        j.setJobCode(r.jobCode());
        j.setJobTitle(r.jobTitle());
        j.setEmployer(r.employer());
        j.setIndustry(r.industry());
        j.setStartDate(r.startDate());
        j.setEndDate(r.endDate());
        j.setCurrent(Boolean.TRUE.equals(r.current()) && r.endDate() == null);
        j.setDuties(r.duties());
        j.setWeeklyHours(r.weeklyHours());
        j.setHazardCodes(r.hazardCodes() == null || r.hazardCodes().isEmpty() ? null : String.join(",", r.hazardCodes()));
    }

    // ------------------------------------------------------------------ surveillance (E03-F01 / E03-HLR-05)

    /**
     * Surveillance due per hazard of the current job(s) with a surveillance interval: last evidence = latest signed
     * surveillance encounter covering the hazard, or the latest result of the programme's device category.
     */
    public List<SurveillanceDue> surveillance(List<JobEntry> entries, Collection<ClinicalEncounter> employeeEncounters,
                                              Collection<Result> employeeResults) {
        Map<String, Hazard> catalogue = hazardCatalogue();
        Set<String> current = new LinkedHashSet<>();
        entries.stream().filter(JobEntry::isCurrent).forEach(j -> current.addAll(hazardCodes(j)));
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        List<SurveillanceDue> out = new ArrayList<>();
        for (String code : current) {
            Hazard h = catalogue.get(code);
            if (h == null || !h.isSurveillanceRequired() || h.getSurveillanceIntervalMonths() == null) {
                continue;
            }
            Programme p = PROGRAMMES.getOrDefault(code, new Programme(h.getName() + " surveillance", null, "SURVEILLANCE_EXAM"));
            Instant last = null;
            for (ClinicalEncounter e : employeeEncounters) {
                if ("SIGNED".equals(e.getStatus()) && p.encounterType().equals(e.getType()) && coversHazard(e, code)) {
                    last = max(last, e.getSignedAt());
                }
            }
            if (p.resultCategory() != null) {
                for (Result r : employeeResults) {
                    if (p.resultCategory().equals(r.getCategory())) {
                        last = max(last, r.getEffectiveAt());
                    }
                }
            }
            LocalDate lastDone = last == null ? null : LocalDate.ofInstant(last, ZoneOffset.UTC);
            LocalDate due = lastDone == null ? today : lastDone.plusMonths(h.getSurveillanceIntervalMonths());
            out.add(new SurveillanceDue(code, p.title(), h.getSurveillanceIntervalMonths(), lastDone, due,
                    lastDone == null || due.isBefore(today)));
        }
        return out;
    }

    private boolean coversHazard(ClinicalEncounter e, String hazard) {
        if (!"SURVEILLANCE_EXAM".equals(e.getType())) {
            return true;
        }
        Object covered = json.map(e.getAnswers()).get("hazards");
        if (covered instanceof Collection<?> c) {
            return c.contains(hazard);
        }
        return covered == null || hazard.equals(covered);
    }

    private static Instant max(Instant a, Instant b) {
        if (b == null) {
            return a;
        }
        return a == null || b.isAfter(a) ? b : a;
    }
}
