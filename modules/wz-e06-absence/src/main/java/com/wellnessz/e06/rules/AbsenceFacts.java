package com.wellnessz.e06.rules;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.model.CaseSource;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OhCaseRepository;
import com.wellnessz.casekernel.model.SafetyCategory;
import com.wellnessz.e06.model.Episode;
import com.wellnessz.e06.model.E06EpisodeRepository;
import com.wellnessz.e06.support.AbsenceSupport;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.masterdata.JobCode;

/** Builds the rule-engine facts of an employee: episodes, job flags (safety-critical, crew) and workplace incident dates. */
@Component
public class AbsenceFacts {

    private final E06EpisodeRepository episodes;
    private final OhCaseRepository cases;
    private final AbsenceSupport support;

    public AbsenceFacts(E06EpisodeRepository episodes, OhCaseRepository cases, AbsenceSupport support) {
        this.episodes = episodes;
        this.cases = cases;
        this.support = support;
    }

    public static RuleEngine.EpisodeFact fact(Episode e) {
        return new RuleEngine.EpisodeFact(e.getId(), e.getSourceSystem(), e.getStartDate(), e.getEndDate(), e.getAbsenceType());
    }

    public List<RuleEngine.EpisodeFact> episodeFacts(String employeeId) {
        return episodes.findByEmployeeIdOrderByStartDateDesc(employeeId).stream().map(AbsenceFacts::fact).toList();
    }

    public RuleEngine.Facts facts(Employee e) {
        JobCode job = support.jobCode(e).orElse(null);
        SafetyCategory safety = job == null ? null : SafetyCategory.parse(job.getSafetyCategory());
        boolean crew = safety == SafetyCategory.FLIGHT_CREW || safety == SafetyCategory.CABIN_CREW;
        boolean safetyCritical = job != null && job.isSafetyCritical();
        List<LocalDate> incidents = cases.findByEmployeeIdOrderByCreatedAtDesc(e.getEmployeeId()).stream()
                .filter(c -> c.getIncidentRef() != null || c.getSource() == CaseSource.EHS_INCIDENT)
                .map(AbsenceFacts::incidentDate).toList();
        return new RuleEngine.Facts(e.getEmployeeId(), e.getSubsidiaryCode(), safetyCritical, crew, episodeFacts(e.getEmployeeId()), incidents);
    }

    private static LocalDate incidentDate(OhCase c) {
        return c.getOccurredOn() != null ? c.getOccurredOn() : LocalDate.ofInstant(c.getCreatedAt(), ZoneOffset.UTC);
    }
}
