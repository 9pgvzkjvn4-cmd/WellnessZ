package com.wellnessz.e07.programme;

import java.time.LocalDate;
import java.time.Period;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Pure eligibility and due-date rules of the surveillance programmes (E07-F01 / F02).
 * <ul>
 *   <li>An employee is eligible when at least one positive criterion matches – licence, job code, hazard of the job,
 *       exposure group (SEG membership, or a SEG of the programme's hazard measured at or above its action level) – and
 *       the department / subsidiary / minimum-age filters (when set) also match. Without positive criteria the filters
 *       alone define the population; a programme without any criterion enrols nobody.</li>
 *   <li>Next due date: last completion + frequency (the over-40 frequency applies when the employee was 40 or older on the
 *       date of the last examination); never examined → enrolment date + grace days (baseline).</li>
 *   <li>Status: EXEMPT (exemption in force) › SCHEDULED (booked) › OVERDUE (due date passed) › DUE (≤ 30 days) › CURRENT.</li>
 * </ul>
 */
public final class Eligibility {

    public static final int DUE_WINDOW_DAYS = 30;

    private Eligibility() {
    }

    /** Programme eligibility criteria (contract §4.1). Empty lists mean "not used". */
    public record Criteria(List<String> jobCodes, List<String> departmentCodes, List<String> subsidiaryCodes, List<String> hazardCodes,
                           List<String> exposureGroups, List<String> licenceTypes, Integer minAge) {
        public Criteria {
            jobCodes = jobCodes == null ? List.of() : List.copyOf(jobCodes);
            departmentCodes = departmentCodes == null ? List.of() : List.copyOf(departmentCodes);
            subsidiaryCodes = subsidiaryCodes == null ? List.of() : List.copyOf(subsidiaryCodes);
            hazardCodes = hazardCodes == null ? List.of() : List.copyOf(hazardCodes);
            exposureGroups = exposureGroups == null ? List.of() : List.copyOf(exposureGroups);
            licenceTypes = licenceTypes == null ? List.of() : List.copyOf(licenceTypes);
        }

        boolean hasPositive() {
            return !jobCodes.isEmpty() || !hazardCodes.isEmpty() || !exposureGroups.isEmpty() || !licenceTypes.isEmpty();
        }

        boolean isEmpty() {
            return !hasPositive() && departmentCodes.isEmpty() && subsidiaryCodes.isEmpty() && minAge == null;
        }
    }

    /**
     * The facts about one person that eligibility uses.
     *
     * @param hazards               hazards of the person's job code
     * @param exposureGroups        SEGs the person belongs to (job code + location)
     * @param hazardsAboveAction    hazards of those SEGs whose latest measurement is at or above the action level
     * @param licences              licence types (from the job's safety category and the person profile)
     */
    public record Person(String employeeId, String jobCode, String departmentCode, String subsidiaryCode, Set<String> hazards,
                         Set<String> exposureGroups, Set<String> hazardsAboveAction, Set<String> licences, LocalDate dateOfBirth) {
        public Person {
            hazards = hazards == null ? Set.of() : Set.copyOf(hazards);
            exposureGroups = exposureGroups == null ? Set.of() : Set.copyOf(exposureGroups);
            hazardsAboveAction = hazardsAboveAction == null ? Set.of() : Set.copyOf(hazardsAboveAction);
            licences = licences == null ? Set.of() : Set.copyOf(licences);
        }
    }

    /** @return the enrolment reason (LICENCE, JOB_CODE, HAZARD, EXPOSURE_GROUP) when the person is eligible */
    public static Optional<String> match(Criteria c, Person p, LocalDate today) {
        if (c == null || c.isEmpty()) {
            return Optional.empty();
        }
        if (!c.departmentCodes().isEmpty() && !c.departmentCodes().contains(p.departmentCode())) {
            return Optional.empty();
        }
        if (!c.subsidiaryCodes().isEmpty() && !c.subsidiaryCodes().contains(p.subsidiaryCode())) {
            return Optional.empty();
        }
        if (c.minAge() != null && p.dateOfBirth() != null && age(p.dateOfBirth(), today) < c.minAge()) {
            return Optional.empty();
        }
        if (!c.hasPositive()) {
            return Optional.of("JOB_CODE");
        }
        if (intersects(c.licenceTypes(), p.licences())) {
            return Optional.of("LICENCE");
        }
        if (p.jobCode() != null && c.jobCodes().contains(p.jobCode())) {
            return Optional.of("JOB_CODE");
        }
        if (intersects(c.hazardCodes(), p.hazards())) {
            return Optional.of("HAZARD");
        }
        if (intersects(c.exposureGroups(), p.exposureGroups()) || intersects(c.hazardCodes(), p.hazardsAboveAction())) {
            return Optional.of("EXPOSURE_GROUP");
        }
        return Optional.empty();
    }

    private static boolean intersects(Collection<String> a, Collection<String> b) {
        for (String x : a) {
            if (b.contains(x)) {
                return true;
            }
        }
        return false;
    }

    public static int age(LocalDate dateOfBirth, LocalDate on) {
        return Period.between(dateOfBirth, on).getYears();
    }

    /** Frequency applying to an examination performed on {@code examinedOn}. */
    public static int frequencyMonths(int frequency, Integer frequencyOver40, LocalDate dateOfBirth, LocalDate examinedOn) {
        if (frequencyOver40 != null && dateOfBirth != null && examinedOn != null && age(dateOfBirth, examinedOn) >= 40) {
            return frequencyOver40;
        }
        return frequency;
    }

    public static LocalDate nextDue(LocalDate lastCompleted, LocalDate enrolledOn, int frequency, Integer frequencyOver40,
                                    LocalDate dateOfBirth, int graceDays) {
        if (lastCompleted == null) {
            return enrolledOn.plusDays(Math.max(0, graceDays));
        }
        return lastCompleted.plusMonths(frequencyMonths(frequency, frequencyOver40, dateOfBirth, lastCompleted));
    }

    /** Date-based status (CURRENT / DUE / OVERDUE) used by compliance figures. */
    public static String dateStatus(LocalDate nextDue, LocalDate today) {
        if (nextDue.isBefore(today)) {
            return "OVERDUE";
        }
        return nextDue.isAfter(today.plusDays(DUE_WINDOW_DAYS)) ? "CURRENT" : "DUE";
    }

    /** Full compliance status of an enrolment. */
    public static String status(LocalDate nextDue, LocalDate today, boolean exempt, LocalDate exemptUntil, boolean scheduled) {
        if (exempt && (exemptUntil == null || !exemptUntil.isBefore(today))) {
            return "EXEMPT";
        }
        if (scheduled) {
            return "SCHEDULED";
        }
        return dateStatus(nextDue, today);
    }
}
