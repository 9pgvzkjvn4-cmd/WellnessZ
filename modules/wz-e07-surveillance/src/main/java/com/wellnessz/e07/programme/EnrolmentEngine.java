package com.wellnessz.e07.programme;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.e07.model.EmployeeProfile;
import com.wellnessz.e07.model.EmployeeProfileRepository;
import com.wellnessz.e07.model.Enrolment;
import com.wellnessz.e07.model.EnrolmentRepository;
import com.wellnessz.e07.model.Programme;
import com.wellnessz.e07.support.SurveillanceSupport;

/**
 * Due-list engine (E07-F02): (re)calculates enrolments of the active population against the ACTIVE programmes, keeps
 * manual enrolments, deactivates people who are no longer eligible and recomputes next due dates.
 */
@Component
public class EnrolmentEngine {

    public static final String GRACE_DAYS_KEY = "surveillance.new-enrolment-grace-days";

    private final Population population;
    private final ProgrammeCatalog catalog;
    private final EnrolmentRepository enrolments;
    private final EmployeeProfileRepository profiles;
    private final SurveillanceSupport support;

    public EnrolmentEngine(Population population, ProgrammeCatalog catalog, EnrolmentRepository enrolments,
                           EmployeeProfileRepository profiles, SurveillanceSupport support) {
        this.population = population;
        this.catalog = catalog;
        this.enrolments = enrolments;
        this.profiles = profiles;
        this.support = support;
    }

    public record RecalcResult(int enrolled, int updated, int removed) {
    }

    /** Recalculates every ACTIVE programme ({@code codes == null}) or the given ones. */
    @Transactional
    public RecalcResult recalculate(Collection<String> codes) {
        LocalDate today = support.today();
        int grace = support.configInt(GRACE_DAYS_KEY, 30);
        Population.Snapshot snap = population.snapshot();
        Map<String, Programme> active = catalog.activeByCode();
        Set<String> scope = codes == null ? null : new HashSet<>(codes);
        int enrolled = 0;
        int updated = 0;
        int removed = 0;
        Map<String, List<Enrolment>> byProgramme = new HashMap<>();
        for (Enrolment e : enrolments.findAll()) {
            byProgramme.computeIfAbsent(e.getProgrammeCode(), k -> new java.util.ArrayList<>()).add(e);
        }
        // programmes without an ACTIVE version: enrolments are deactivated
        for (Map.Entry<String, List<Enrolment>> entry : byProgramme.entrySet()) {
            if (!active.containsKey(entry.getKey()) && (scope == null || scope.contains(entry.getKey()))) {
                for (Enrolment e : entry.getValue()) {
                    if (e.isActive()) {
                        e.setActive(false);
                        e.setUpdatedAt(support.now());
                        removed++;
                    }
                }
            }
        }
        for (Programme p : active.values()) {
            if (scope != null && !scope.contains(p.getCode())) {
                continue;
            }
            Eligibility.Criteria criteria = catalog.criteria(p);
            Map<String, Enrolment> existing = new HashMap<>();
            byProgramme.getOrDefault(p.getCode(), List.of()).forEach(e -> existing.put(e.getEmployeeId(), e));
            for (Eligibility.Person person : snap.persons().values()) {
                Optional<String> reason = Eligibility.match(criteria, person, today);
                Enrolment e = existing.remove(person.employeeId());
                if (reason.isPresent()) {
                    if (e == null) {
                        e = newEnrolment(person.employeeId(), p.getCode(), reason.get(), today);
                        e.setNextDueOn(Eligibility.nextDue(null, today, p.getFrequencyMonths(), p.getFrequencyMonthsOver40(),
                                person.dateOfBirth(), grace));
                        enrolments.save(e);
                        enrolled++;
                        continue;
                    }
                    if (!e.isActive()) {
                        e.setActive(true);
                        enrolled++;
                    }
                    if (!"MANUAL".equals(e.getReason()) && !reason.get().equals(e.getReason())) {
                        e.setReason(reason.get());
                    }
                    if (refreshDue(e, p, person.dateOfBirth(), grace)) {
                        updated++;
                    }
                    e.setUpdatedAt(support.now());
                } else if (e != null && e.isActive()) {
                    if ("MANUAL".equals(e.getReason()) || "EXPOSURE_GROUP".equals(e.getReason()) && stillInSeg(person)) {
                        if (refreshDue(e, p, person.dateOfBirth(), grace)) {
                            updated++;
                        }
                        continue;
                    }
                    e.setActive(false);
                    e.setUpdatedAt(support.now());
                    removed++;
                }
            }
            // enrolments of people no longer in the active HRIS population
            for (Enrolment e : existing.values()) {
                if (e.isActive() && !snap.employees().containsKey(e.getEmployeeId())) {
                    e.setActive(false);
                    e.setUpdatedAt(support.now());
                    removed++;
                }
            }
        }
        return new RecalcResult(enrolled, updated, removed);
    }

    private static boolean stillInSeg(Eligibility.Person person) {
        return !person.exposureGroups().isEmpty();
    }

    /** Enrols (or re-activates) one employee in a programme; returns {@code true} when newly enrolled. */
    @Transactional
    public boolean enrol(String employeeId, Programme p, String reason) {
        Enrolment e = enrolments.findByEmployeeIdAndProgrammeCode(employeeId, p.getCode()).orElse(null);
        if (e != null && e.isActive()) {
            return false;
        }
        int grace = support.configInt(GRACE_DAYS_KEY, 30);
        LocalDate dob = profiles.findById(employeeId).map(EmployeeProfile::getDateOfBirth).orElse(null);
        if (e == null) {
            e = newEnrolment(employeeId, p.getCode(), reason, support.today());
            e.setNextDueOn(Eligibility.nextDue(null, support.today(), p.getFrequencyMonths(), p.getFrequencyMonthsOver40(), dob, grace));
            enrolments.save(e);
        } else {
            e.setActive(true);
            e.setReason(reason);
            refreshDue(e, p, dob, grace);
            e.setUpdatedAt(support.now());
        }
        return true;
    }

    public LocalDate dateOfBirth(String employeeId) {
        return profiles.findById(employeeId).map(EmployeeProfile::getDateOfBirth).orElse(null);
    }

    /** Recomputes the next due date; returns {@code true} when it changed. */
    public boolean refreshDue(Enrolment e, Programme p, LocalDate dateOfBirth, int graceDays) {
        LocalDate next = Eligibility.nextDue(e.getLastCompletedOn(), e.getEnrolledOn(), p.getFrequencyMonths(), p.getFrequencyMonthsOver40(),
                dateOfBirth, graceDays);
        if (!next.equals(e.getNextDueOn())) {
            e.setNextDueOn(next);
            return true;
        }
        return false;
    }

    public int graceDays() {
        return support.configInt(GRACE_DAYS_KEY, 30);
    }

    private Enrolment newEnrolment(String employeeId, String programmeCode, String reason, LocalDate today) {
        Enrolment e = new Enrolment();
        e.setId(UUID.randomUUID());
        e.setEmployeeId(employeeId);
        e.setProgrammeCode(programmeCode);
        e.setReason(reason);
        e.setActive(true);
        e.setEnrolledOn(today);
        e.setUpdatedAt(support.now());
        return e;
    }
}
