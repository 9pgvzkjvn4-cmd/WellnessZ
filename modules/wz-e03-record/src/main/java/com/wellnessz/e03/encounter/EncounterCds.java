package com.wellnessz.e03.encounter;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.e02.model.DurationGuideline;
import com.wellnessz.e02.model.DurationGuidelineRepository;
import com.wellnessz.e03.cds.CdsAlert;
import com.wellnessz.e03.chart.JobHistoryService;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.masterdata.JobCode;
import com.wellnessz.masterdata.JobCodeRepository;
import com.wellnessz.masterdata.fitness.FitnessStandard;
import com.wellnessz.masterdata.fitness.FitnessStandardRepository;

/**
 * Encounter-level decision support (E03-F05, E03-BL-014): protocol prompts from the template, overdue exposure
 * surveillance, duration-guideline comparison for the case's primary ICD-10 diagnosis (E02 guideline table) and the
 * applicable fitness standard for fitness / crew / food-handler templates.
 */
@Component
public class EncounterCds {

    public record StandardRef(String code, int version, String title, String source, String referenceUrl, String requiresSignoffRole) {
    }

    private final FitnessStandardRepository standards;
    private final JobCodeRepository jobCodes;
    private final DurationGuidelineRepository guidelines;
    private final Clock clock;

    public EncounterCds(FitnessStandardRepository standards, JobCodeRepository jobCodes, DurationGuidelineRepository guidelines, Clock clock) {
        this.standards = standards;
        this.jobCodes = jobCodes;
        this.guidelines = guidelines;
        this.clock = clock;
    }

    /** The standard explicitly chosen on the encounter, else the default for the template / the employee's safety category. */
    public Optional<StandardRef> standard(EncounterTemplates.Definition template, String explicitCode, Employee e) {
        if (!template.fitnessStandardPrompt() && explicitCode == null) {
            return Optional.empty();
        }
        String code = explicitCode != null ? explicitCode : defaultStandard(template.type(), safetyCategory(e));
        return standards.current(code).map(EncounterCds::ref);
    }

    static StandardRef ref(FitnessStandard s) {
        return new StandardRef(s.getCode(), s.getVersion(), s.getTitle(), s.getSource(), s.getReferenceUrl(), s.getRequiresSignoffRole());
    }

    String safetyCategory(Employee e) {
        return e.getJobCode() == null ? null : jobCodes.findById(e.getJobCode()).map(JobCode::getSafetyCategory).orElse(null);
    }

    static String defaultStandard(String encounterType, String safetyCategory) {
        if ("FOOD_HANDLER".equals(encounterType)) {
            return "FOOD_HANDLER";
        }
        if ("CREW_AEROMEDICAL".equals(encounterType)) {
            return "FLIGHT_CREW".equals(safetyCategory) ? "CAA_CLASS1" : "CABIN_CREW";
        }
        if (safetyCategory == null) {
            return "GENERAL_DUTIES";
        }
        return switch (safetyCategory) {
            case "FLIGHT_CREW" -> "CAA_CLASS1";
            case "CABIN_CREW" -> "CABIN_CREW";
            case "AIRSIDE_DRIVER" -> "AIRSIDE_DRIVING";
            case "FOOD_HANDLER" -> "FOOD_HANDLER";
            case "FORKLIFT" -> "FORKLIFT";
            case "WORKING_AT_HEIGHT" -> "WORKING_AT_HEIGHT";
            default -> "GENERAL_DUTIES";
        };
    }

    public List<CdsAlert> alerts(EncounterTemplates.Definition template, Optional<StandardRef> standard, OhCase c,
                                 List<JobHistoryService.SurveillanceDue> surveillance) {
        List<CdsAlert> alerts = new ArrayList<>();
        if (template.protocolPrompts() != null) {
            template.protocolPrompts().forEach(p -> alerts.add(new CdsAlert(CdsAlert.PROTOCOL_PROMPT, "INFO", p,
                    "Encounter template " + template.type() + " v" + template.version())));
        }
        standard.ifPresent(s -> alerts.add(new CdsAlert(CdsAlert.FITNESS_STANDARD, "INFO",
                "Assess against " + s.title() + " (" + s.code() + " v" + s.version() + ")" + (s.requiresSignoffRole() == null ? ""
                        : "; sign-off by " + s.requiresSignoffRole() + " required"),
                s.referenceUrl() != null ? s.referenceUrl() : s.source())));
        for (JobHistoryService.SurveillanceDue d : surveillance) {
            if (d.overdue()) {
                alerts.add(new CdsAlert(CdsAlert.SURVEILLANCE_DUE, "WARNING",
                        d.programme() + (d.lastDone() == null ? " – no previous result on record" : " overdue since " + d.dueDate()),
                        "Hazard " + d.hazardCode() + ", interval " + d.intervalMonths() + " months"));
            }
        }
        duration(c).ifPresent(alerts::add);
        return alerts;
    }

    /** DURATION_EXCEEDED when the case has lasted longer than the guideline for its primary ICD-10 diagnosis. */
    Optional<CdsAlert> duration(OhCase c) {
        if (c == null || !"ICD_10".equals(c.getPrimaryDiagnosisSystem()) || c.getPrimaryDiagnosisCode() == null) {
            return Optional.empty();
        }
        Optional<DurationGuideline> g = guideline(c.getPrimaryDiagnosisCode());
        if (g.isEmpty()) {
            return Optional.empty();
        }
        Instant start = c.getOccurredOn() != null ? c.getOccurredOn().atStartOfDay(java.time.ZoneOffset.UTC).toInstant() : c.getCreatedAt();
        long days = Duration.between(start, clock.instant()).toDays();
        DurationGuideline dg = g.get();
        if (days <= dg.getExpectedDays()) {
            return Optional.empty();
        }
        return Optional.of(new CdsAlert(CdsAlert.DURATION_EXCEEDED, "WARNING",
                "Case duration " + days + " days exceeds the guideline of " + dg.getExpectedDays() + " days for " + dg.getTitle(),
                dg.getSource()));
    }

    /** Exact code first, then shorter prefixes (S93.40 → S93.4 → S93). */
    Optional<DurationGuideline> guideline(String code) {
        String c = code;
        while (c.length() >= 3) {
            Optional<DurationGuideline> g = guidelines.findById(c);
            if (g.isPresent()) {
                return g;
            }
            c = c.substring(0, c.length() - 1);
            if (c.endsWith(".")) {
                c = c.substring(0, c.length() - 1);
            }
        }
        return Optional.empty();
    }
}
