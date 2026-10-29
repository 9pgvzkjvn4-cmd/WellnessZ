package com.wellnessz.e03.chart;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OhCaseRepository;
import com.wellnessz.casekernel.support.ChartContributor;
import com.wellnessz.e03.model.Allergy;
import com.wellnessz.e03.model.AllergyRepository;
import com.wellnessz.e03.model.ClinicalEncounter;
import com.wellnessz.e03.model.ClinicalEncounterRepository;
import com.wellnessz.e03.model.Immunisation;
import com.wellnessz.e03.model.ImmunisationRepository;
import com.wellnessz.e03.model.JobEntry;
import com.wellnessz.e03.model.MedicationRepository;
import com.wellnessz.e03.model.Problem;
import com.wellnessz.e03.model.ProblemRepository;
import com.wellnessz.e03.model.Result;
import com.wellnessz.e03.model.ResultRepository;
import com.wellnessz.e03.support.RecordGuard;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.masterdata.JobCode;
import com.wellnessz.masterdata.JobCodeRepository;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Employee OH chart (E03-F01 / E03-BL-002): one summary call (target &lt; 2 s) with alerts, fitness and restrictions
 * (contributed by E05 through {@link ChartContributor}), open cases, exposure-driven surveillance due dates, immunisations
 * and counts; restricted-class items are hidden from callers without restricted access and only counted.
 */
@Service
public class ChartService {

    private static final Logger log = LoggerFactory.getLogger(ChartService.class);

    private final RecordGuard guard;
    private final ProblemRepository problems;
    private final AllergyRepository allergies;
    private final MedicationRepository medications;
    private final ImmunisationRepository immunisations;
    private final ClinicalEncounterRepository encounters;
    private final ResultRepository results;
    private final OhCaseRepository cases;
    private final JobCodeRepository jobCodes;
    private final JobHistoryService jobs;
    private final List<ChartContributor> contributors;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public ChartService(RecordGuard guard, ProblemRepository problems, AllergyRepository allergies, MedicationRepository medications,
                        ImmunisationRepository immunisations, ClinicalEncounterRepository encounters, ResultRepository results, OhCaseRepository cases,
                        JobCodeRepository jobCodes, JobHistoryService jobs, List<ChartContributor> contributors, JdbcTemplate jdbc,
                        AuditService audit) {
        this.guard = guard;
        this.problems = problems;
        this.allergies = allergies;
        this.medications = medications;
        this.immunisations = immunisations;
        this.encounters = encounters;
        this.results = results;
        this.cases = cases;
        this.jobCodes = jobCodes;
        this.jobs = jobs;
        this.contributors = List.copyOf(contributors);
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ records

    public record EmployeeBlock(String employeeId, String displayName, LocalDate dateOfBirth, String subsidiaryCode, String departmentCode,
                                String jobCode, String jobTitle, String locationCode, String safetyCategory) {
    }

    public record Alert(String kind, String severity, String text) {
    }

    public record OpenCase(UUID id, String caseRef, String status, String caseType) {
    }

    public record ImmunisationSummary(String vaccine, LocalDate date, String status, LocalDate nextDueDate) {
    }

    public record Counts(int problems, int medications, int allergies, int results, int unacknowledgedAbnormal, int documents,
                         int unsignedEncounters) {
    }

    public record ChartSummary(EmployeeBlock employee, List<Alert> alerts, @JsonInclude(JsonInclude.Include.ALWAYS) Object fitness,
                               Object restrictions, List<OpenCase> openCases, List<JobHistoryService.SurveillanceDue> surveillanceDue,
                               List<ImmunisationSummary> immunisations, Counts counts, int restrictedItemsHidden,
                               Map<String, Object> sections) {
    }

    public record TimelineEntry(Instant at, String kind, String ref, String title) {
    }

    record DocumentMeta(UUID id, String title, String type, String classification, Instant createdAt) {
    }

    // ------------------------------------------------------------------ summary

    @Transactional
    public ChartSummary summary(CurrentUser user, String employeeId) {
        RecordGuard.Access a = guard.read(user, employeeId, "CHART_READ");
        Employee e = a.employee();
        int[] hidden = {0};
        List<Problem> problemList = visible(problems.findByEmployeeIdOrderByRecordedAtDesc(employeeId), a, Problem::getClassification, hidden);
        List<Allergy> allergyList = allergies.findByEmployeeIdOrderByRecordedAtDesc(employeeId).stream()
                .filter(x -> "ACTIVE".equals(x.getStatus())).toList();
        long meds = medications.findByEmployeeIdOrderByRecordedAtDesc(employeeId).stream()
                .filter(m -> "ACTIVE".equals(m.getStatus())).filter(m -> {
                    boolean ok = a.canSee(m.getClassification());
                    if (!ok) {
                        hidden[0]++;
                    }
                    return ok;
                }).count();
        List<ClinicalEncounter> allEncounters = encounters.findByEmployeeIdOrderByCreatedAtDesc(employeeId);
        List<ClinicalEncounter> encounterList = visible(allEncounters, a, ClinicalEncounter::getClassification, hidden);
        List<Result> allResults = results.findByEmployeeIdOrderByEffectiveAtDesc(employeeId);
        List<Result> resultList = visible(allResults, a, Result::getClassification, hidden);
        List<DocumentMeta> docs = visible(documents(employeeId), a, DocumentMeta::classification, hidden);
        List<OhCase> caseList = cases.findByEmployeeIdOrderByCreatedAtDesc(employeeId);
        List<OpenCase> open = new ArrayList<>();
        for (OhCase c : caseList) {
            if (!c.isOpen()) {
                continue;
            }
            if (!a.canSee(c.getClassification().name())) {
                hidden[0]++;
                continue;
            }
            open.add(new OpenCase(c.getId(), c.getCaseRef(), c.getStatus().name(), c.getCaseType() == null ? null : c.getCaseType().name()));
        }
        List<JobEntry> jobList = jobs.sync(e);
        List<JobHistoryService.SurveillanceDue> due = jobs.surveillance(jobList, encounterList, resultList);
        List<Alert> alerts = new ArrayList<>();
        for (Allergy x : allergyList) {
            String severity = "HIGH".equals(x.getCriticality()) ? "HIGH" : "LOW".equals(x.getCriticality()) ? "LOW" : "MEDIUM";
            alerts.add(new Alert("ALLERGY", severity, x.getSubstanceDisplay() + (x.getReaction() == null ? "" : " – " + x.getReaction())));
        }
        for (JobHistoryService.SurveillanceDue d : due) {
            if (d.overdue()) {
                alerts.add(new Alert("OVERDUE_SURVEILLANCE", "MEDIUM", d.lastDone() == null
                        ? d.programme() + " – no result on record" : d.programme() + " overdue since " + d.dueDate()));
            }
        }
        List<Result> unack = resultList.stream().filter(r -> r.getAcknowledgedAt() == null && !"NORMAL".equals(r.getFlag())).toList();
        if (!unack.isEmpty()) {
            boolean critical = unack.stream().anyMatch(r -> "CRITICAL".equals(r.getFlag()));
            alerts.add(new Alert("ABNORMAL_RESULT", critical ? "HIGH" : "MEDIUM",
                    unack.size() + " abnormal result(s) awaiting acknowledgement"));
        }
        Map<String, Object> sections = new LinkedHashMap<>();
        for (ChartContributor c : contributors) {
            try {
                ChartContributor.Contribution contribution = c.contribute(user, employeeId);
                if (contribution != null) {
                    sections.putAll(contribution.sections());
                    contribution.alerts().forEach(x -> alerts.add(new Alert(x.kind(), x.severity(), x.text())));
                }
            } catch (RuntimeException ex) {
                log.warn("Chart contributor {} failed: {}", c.getClass().getSimpleName(), ex.getClass().getSimpleName());
            }
        }
        Object fitness = sections.remove("fitness");
        Object restrictions = sections.remove("restrictions");
        Map<String, ImmunisationSummary> latest = new LinkedHashMap<>();
        for (Immunisation i : immunisations.findByEmployeeIdOrderByAdministeredOnDesc(employeeId)) {
            latest.putIfAbsent(i.getVaccine(), new ImmunisationSummary(i.getVaccine(), i.getAdministeredOn(), i.getStatus(), i.getNextDueDate()));
        }
        int unsigned = (int) encounterList.stream().filter(x -> "DRAFT".equals(x.getStatus())).count();
        Counts counts = new Counts((int) problemList.stream().filter(p -> "ACTIVE".equals(p.getStatus())).count(), (int) meds,
                allergyList.size(), resultList.size(), unack.size(), docs.size(), unsigned);
        JobCode jc = e.getJobCode() == null ? null : jobCodes.findById(e.getJobCode()).orElse(null);
        EmployeeBlock block = new EmployeeBlock(e.getEmployeeId(), e.getDisplayName(), null, e.getSubsidiaryCode(), e.getDepartmentCode(),
                e.getJobCode(), jc == null ? null : jc.getTitle(), e.getLocationCode(), jc == null ? null : jc.getSafetyCategory());
        audit.record("CHART_READ", "EMPLOYEE", employeeId, AuditService.Outcome.SUCCESS, "restrictedHidden=" + hidden[0]);
        return new ChartSummary(block, alerts, fitness, restrictions == null ? List.of() : restrictions, open, due,
                new ArrayList<>(latest.values()), counts, hidden[0], sections.isEmpty() ? null : sections);
    }

    private static <T> List<T> visible(List<T> items, RecordGuard.Access a, java.util.function.Function<T, String> cls, int[] hidden) {
        List<T> out = new ArrayList<>(items.size());
        for (T t : items) {
            if (a.canSee(cls.apply(t))) {
                out.add(t);
            } else {
                hidden[0]++;
            }
        }
        return out;
    }

    /** Document metadata only (no content decryption) for counts and the record timeline. */
    List<DocumentMeta> documents(String employeeId) {
        return jdbc.query("""
                select id, title, type, classification, created_at from docs.document
                 where employee_id = ? and status <> 'REVOKED' order by created_at desc
                """, (rs, i) -> new DocumentMeta(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getTimestamp(5).toInstant()), employeeId);
    }

    // ------------------------------------------------------------------ record timeline

    @Transactional(readOnly = true)
    public List<TimelineEntry> timeline(CurrentUser user, String employeeId) {
        RecordGuard.Access a = guard.read(user, employeeId, "CHART_TIMELINE_READ");
        int[] hidden = {0};
        List<TimelineEntry> out = new ArrayList<>();
        for (ClinicalEncounter x : visible(encounters.findByEmployeeIdOrderByCreatedAtDesc(employeeId), a, ClinicalEncounter::getClassification, hidden)) {
            out.add(new TimelineEntry(x.getSignedAt() != null ? x.getSignedAt() : x.getCreatedAt(), "ENCOUNTER", x.getId().toString(),
                    x.getTitle() + " (" + x.getStatus() + ")"));
        }
        for (Result r : visible(results.findByEmployeeIdOrderByEffectiveAtDesc(employeeId), a, Result::getClassification, hidden)) {
            out.add(new TimelineEntry(r.getEffectiveAt(), "RESULT", r.getId().toString(), r.getCodeDisplay() + ": " + r.getFlag()));
        }
        for (DocumentMeta d : visible(documents(employeeId), a, DocumentMeta::classification, hidden)) {
            out.add(new TimelineEntry(d.createdAt(), "DOCUMENT", d.id().toString(), d.title()));
        }
        for (Problem p : visible(problems.findByEmployeeIdOrderByRecordedAtDesc(employeeId), a, Problem::getClassification, hidden)) {
            out.add(new TimelineEntry(p.getRecordedAt(), "PROBLEM", p.getId().toString(), p.getCodeDisplay() + " (" + p.getStatus() + ")"));
        }
        for (OhCase c : cases.findByEmployeeIdOrderByCreatedAtDesc(employeeId)) {
            if (a.canSee(c.getClassification().name())) {
                out.add(new TimelineEntry(c.getCreatedAt(), "CASE", c.getCaseRef(), "Case " + c.getCaseRef() + " opened ("
                        + (c.getCaseType() == null ? c.getCategory().name() : c.getCaseType().name()) + ")"));
            }
        }
        for (ChartContributor contributor : contributors) {
            try {
                Object entries = contributor.contribute(user, employeeId).sections().get("timeline");
                if (entries instanceof List<?> list) {
                    for (Object o : list) {
                        if (o instanceof Map<?, ?> m && m.get("at") != null) {
                            out.add(new TimelineEntry(instant(m.get("at")), String.valueOf(m.get("kind") == null ? "FITNESS" : m.get("kind")),
                                    m.get("ref") == null ? null : String.valueOf(m.get("ref")), String.valueOf(m.get("title"))));
                        }
                    }
                }
            } catch (RuntimeException ex) {
                log.warn("Chart contributor {} timeline failed: {}", contributor.getClass().getSimpleName(), ex.getClass().getSimpleName());
            }
        }
        out.sort(Comparator.comparing(TimelineEntry::at, Comparator.nullsLast(Comparator.reverseOrder())));
        List<TimelineEntry> limited = out.size() > 500 ? out.subList(0, 500) : out;
        audit.record("CHART_TIMELINE_READ", "EMPLOYEE", employeeId, AuditService.Outcome.SUCCESS, "rows=" + limited.size());
        return List.copyOf(limited);
    }

    private static Instant instant(Object o) {
        if (o instanceof Instant i) {
            return i;
        }
        if (o instanceof Timestamp ts) {
            return ts.toInstant();
        }
        if (o instanceof LocalDate d) {
            return d.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        }
        try {
            return Instant.parse(String.valueOf(o));
        } catch (java.time.format.DateTimeParseException ex) {
            return LocalDate.parse(String.valueOf(o)).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        }
    }
}
