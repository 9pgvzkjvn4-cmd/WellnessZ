package com.wellnessz.e07.programme;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.wellnessz.e07.model.Enrolment;
import com.wellnessz.masterdata.Employee;

class EligibilityTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 29);

    static Eligibility.Person person(String id, String job, String dept, String sub, Set<String> hazards, Set<String> segs, Set<String> above,
                                     Set<String> licences, LocalDate dob) {
        return new Eligibility.Person(id, job, dept, sub, hazards, segs, above, licences, dob);
    }

    static Eligibility.Criteria criteria(List<String> jobs, List<String> hazards, List<String> licences) {
        return new Eligibility.Criteria(jobs, null, null, hazards, null, licences, null);
    }

    @Test
    void matchesByLicenceJobHazardAndExposureGroup() {
        Eligibility.Person pilot = person("E1", "PILOT", "FLIGHTOPS", "FLT", Set.of("NOISE"), Set.of(), Set.of(), Set.of("FLIGHT_CREW"), null);
        Eligibility.Person ramp = person("E2", "RAMP_AGENT", "GROUND", "GND", Set.of("NOISE", "DGR"), Set.of("LHR-RAMP-NOISE"), Set.of("NOISE"),
                Set.of(), null);
        Eligibility.Person forklift = person("E3", "FORKLIFT_OP", "CARGO", "CGO", Set.of(), Set.of("LHR-CARGO-NOISE"), Set.of("NOISE"), Set.of(), null);
        Eligibility.Person office = person("E4", "OFFICE_ADMIN", "CORP", "CORP", Set.of("VDU"), Set.of(), Set.of(), Set.of(), null);

        assertThat(Eligibility.match(criteria(null, null, List.of("FLIGHT_CREW")), pilot, TODAY)).contains("LICENCE");
        assertThat(Eligibility.match(criteria(List.of("RAMP_AGENT"), null, null), ramp, TODAY)).contains("JOB_CODE");
        Eligibility.Criteria hearing = criteria(null, List.of("NOISE"), null);
        assertThat(Eligibility.match(hearing, ramp, TODAY)).contains("HAZARD");
        // not a NOISE job, but its SEG measured at / above the action level → exposure group enrolment
        assertThat(Eligibility.match(hearing, forklift, TODAY)).contains("EXPOSURE_GROUP");
        assertThat(Eligibility.match(hearing, office, TODAY)).isEmpty();
        assertThat(Eligibility.match(new Eligibility.Criteria(null, null, null, null, List.of("LHR-RAMP-NOISE"), null, null), ramp, TODAY))
                .contains("EXPOSURE_GROUP");
    }

    @Test
    void filtersRestrictAndEmptyCriteriaEnrolNobody() {
        Eligibility.Person ramp = person("E2", "RAMP_AGENT", "GROUND", "GND", Set.of("NOISE"), Set.of(), Set.of(), Set.of(), LocalDate.of(2000, 1, 1));
        assertThat(Eligibility.match(new Eligibility.Criteria(null, List.of("CARGO"), null, List.of("NOISE"), null, null, null), ramp, TODAY)).isEmpty();
        assertThat(Eligibility.match(new Eligibility.Criteria(null, null, List.of("GND"), List.of("NOISE"), null, null, null), ramp, TODAY))
                .contains("HAZARD");
        assertThat(Eligibility.match(new Eligibility.Criteria(null, null, null, List.of("NOISE"), null, null, 40), ramp, TODAY)).isEmpty();
        assertThat(Eligibility.match(new Eligibility.Criteria(null, List.of("GROUND"), null, null, null, null, null), ramp, TODAY))
                .contains("JOB_CODE");
        assertThat(Eligibility.match(new Eligibility.Criteria(null, null, null, null, null, null, null), ramp, TODAY)).isEmpty();
    }

    @Test
    void nextDueUsesFrequencyOver40AndGrace() {
        LocalDate dob = LocalDate.of(1979, 5, 10);
        // examined at 46 → 6-month Class 1 frequency
        assertThat(Eligibility.nextDue(LocalDate.of(2026, 6, 1), TODAY, 12, 6, dob, 30)).isEqualTo(LocalDate.of(2026, 12, 1));
        // examined at 39 (day before 40th birthday) → 12 months
        assertThat(Eligibility.nextDue(LocalDate.of(2019, 5, 9), TODAY, 12, 6, dob, 30)).isEqualTo(LocalDate.of(2020, 5, 9));
        assertThat(Eligibility.nextDue(LocalDate.of(2019, 5, 10), TODAY, 12, 6, dob, 30)).isEqualTo(LocalDate.of(2019, 11, 10));
        // unknown date of birth → standard frequency
        assertThat(Eligibility.nextDue(LocalDate.of(2026, 6, 1), TODAY, 12, 6, null, 30)).isEqualTo(LocalDate.of(2027, 6, 1));
        // never examined → baseline due after the grace period
        assertThat(Eligibility.nextDue(null, TODAY, 12, null, null, 30)).isEqualTo(TODAY.plusDays(30));
    }

    @Test
    void statusPrecedence() {
        assertThat(Eligibility.status(TODAY.minusDays(1), TODAY, false, null, false)).isEqualTo("OVERDUE");
        assertThat(Eligibility.status(TODAY, TODAY, false, null, false)).isEqualTo("DUE");
        assertThat(Eligibility.status(TODAY.plusDays(30), TODAY, false, null, false)).isEqualTo("DUE");
        assertThat(Eligibility.status(TODAY.plusDays(31), TODAY, false, null, false)).isEqualTo("CURRENT");
        assertThat(Eligibility.status(TODAY.minusDays(5), TODAY, false, null, true)).isEqualTo("SCHEDULED");
        assertThat(Eligibility.status(TODAY.minusDays(5), TODAY, true, null, true)).isEqualTo("EXEMPT");
        assertThat(Eligibility.status(TODAY.minusDays(5), TODAY, true, TODAY.minusDays(1), false)).isEqualTo("OVERDUE");
    }

    @Test
    void simulationCountsPopulationImpactWithoutWrites() {
        Map<String, Employee> emps = new LinkedHashMap<>();
        Map<String, Eligibility.Person> persons = new LinkedHashMap<>();
        String[][] people = {{"E1", "RAMP_AGENT", "GROUND", "GND"}, {"E2", "RAMP_AGENT", "GROUND", "GND"}, {"E3", "ENGINEER", "ENG", "ENG"},
            {"E4", "OFFICE_ADMIN", "CORP", "CORP"}};
        for (String[] p : people) {
            emps.put(p[0], new Employee(p[0], p[0], p[3], p[2], Instant.now()));
            persons.put(p[0], person(p[0], p[1], p[2], p[3], "OFFICE_ADMIN".equals(p[1]) ? Set.of("VDU") : Set.of("NOISE"), Set.of(), Set.of(),
                    Set.of(), null));
        }
        Enrolment existing = new Enrolment();
        existing.setId(UUID.randomUUID());
        existing.setEmployeeId("E1");
        existing.setEnrolledOn(TODAY.minusYears(3));
        existing.setLastCompletedOn(TODAY.minusMonths(13));
        ProgrammeService.Simulation s = ProgrammeService.compute("HEARING_CONSERVATION", 2, criteria(null, List.of("NOISE"), null), 12, null,
                new Population.Snapshot(emps, persons), Map.of("E1", existing), 30, TODAY);
        assertThat(s.eligibleHeadcount()).isEqualTo(3);
        assertThat(s.newlyEnrolled()).isEqualTo(2);
        assertThat(s.wouldBeOverdue()).isEqualTo(1);
        assertThat(s.byUnit()).hasSize(2);
        assertThat(s.byUnit().get(0)).isEqualTo(new ProgrammeService.UnitCount("GND", "GROUND", 2));
    }
}
