package com.wellnessz.e07.programme;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.wellnessz.e07.model.EmployeeProfile;
import com.wellnessz.e07.model.EmployeeProfileRepository;
import com.wellnessz.e07.model.ExposureGroup;
import com.wellnessz.e07.model.ExposureGroupRepository;
import com.wellnessz.e07.model.IhMeasurement;
import com.wellnessz.e07.model.IhMeasurementRepository;
import com.wellnessz.e07.support.SurveillanceSupport;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.masterdata.JobCode;

/** Builds the eligibility facts ({@link Eligibility.Person}) of the active HRIS population. */
@Component
public class Population {

    private final SurveillanceSupport support;
    private final ExposureGroupRepository segs;
    private final IhMeasurementRepository measurements;
    private final EmployeeProfileRepository profiles;

    public Population(SurveillanceSupport support, ExposureGroupRepository segs, IhMeasurementRepository measurements,
                      EmployeeProfileRepository profiles) {
        this.support = support;
        this.segs = segs;
        this.measurements = measurements;
        this.profiles = profiles;
    }

    /** The population at one point in time: HRIS employees with their eligibility facts. */
    public record Snapshot(Map<String, Employee> employees, Map<String, Eligibility.Person> persons) {
    }

    /** A SEG with its latest measurement. */
    public record SegState(ExposureGroup seg, IhMeasurement latest, boolean aboveActionLevel) {
    }

    public Map<String, SegState> segStates() {
        Map<String, SegState> out = new LinkedHashMap<>();
        for (ExposureGroup g : segs.findAllByOrderByCodeAsc()) {
            IhMeasurement latest = measurements.findFirstBySegCodeOrderByMeasuredOnDescImportedAtDesc(g.getCode()).orElse(null);
            boolean above = g.isActive() && latest != null && latest.getValue().compareTo(g.getActionLevel()) >= 0;
            out.put(g.getCode(), new SegState(g, latest, above));
        }
        return out;
    }

    public Snapshot snapshot() {
        return snapshot(support.activeEmployees());
    }

    public Snapshot snapshot(List<Employee> employees) {
        Map<String, JobCode> jobs = support.jobCodes();
        Map<String, SegState> segStates = segStates();
        Map<String, EmployeeProfile> profileMap = new HashMap<>();
        profiles.findAll().forEach(p -> profileMap.put(p.getEmployeeId(), p));
        Map<String, Employee> emp = new LinkedHashMap<>();
        Map<String, Eligibility.Person> persons = new LinkedHashMap<>();
        for (Employee e : employees) {
            emp.put(e.getEmployeeId(), e);
            persons.put(e.getEmployeeId(), person(e, jobs, segStates, profileMap.get(e.getEmployeeId())));
        }
        return new Snapshot(emp, persons);
    }

    public Eligibility.Person person(Employee e) {
        return snapshot(List.of(e)).persons().get(e.getEmployeeId());
    }

    /** Eligibility facts of a job (pre-employment panel): no person, location and age unknown. */
    public Eligibility.Person forJob(String jobCode, String departmentCode, String subsidiaryCode, String locationCode) {
        JobCode j = support.jobCode(jobCode).orElse(null);
        Set<String> segCodes = new HashSet<>();
        Set<String> above = new HashSet<>();
        segStates().values().forEach(s -> {
            if (s.seg().isActive() && SurveillanceSupport.csv(s.seg().getJobCodes()).contains(jobCode)
                    && (locationCode == null || s.seg().getLocationCode().equals(locationCode))) {
                segCodes.add(s.seg().getCode());
                if (s.aboveActionLevel()) {
                    above.add(s.seg().getHazardCode());
                }
            }
        });
        Set<String> licences = new HashSet<>();
        if (j != null && j.getSafetyCategory() != null) {
            licences.add(j.getSafetyCategory());
        }
        return new Eligibility.Person(null, jobCode, departmentCode, subsidiaryCode,
                j == null ? Set.of() : Set.copyOf(SurveillanceSupport.csv(j.getHazardCodes())), segCodes, above, licences, null);
    }

    static Eligibility.Person person(Employee e, Map<String, JobCode> jobs, Map<String, SegState> segStates, EmployeeProfile profile) {
        JobCode j = e.getJobCode() == null ? null : jobs.get(e.getJobCode());
        Set<String> hazards = j == null ? Set.of() : Set.copyOf(SurveillanceSupport.csv(j.getHazardCodes()));
        Set<String> segCodes = new HashSet<>();
        Set<String> above = new HashSet<>();
        for (SegState s : segStates.values()) {
            ExposureGroup g = s.seg();
            if (g.isActive() && e.getJobCode() != null && SurveillanceSupport.csv(g.getJobCodes()).contains(e.getJobCode())
                    && g.getLocationCode().equals(e.getLocationCode())) {
                segCodes.add(g.getCode());
                if (s.aboveActionLevel()) {
                    above.add(g.getHazardCode());
                }
            }
        }
        Set<String> licences = new HashSet<>();
        if (j != null && j.getSafetyCategory() != null) {
            licences.add(j.getSafetyCategory());
        }
        LocalDate dob = null;
        if (profile != null) {
            licences.addAll(SurveillanceSupport.csv(profile.getLicenceTypes()));
            dob = profile.getDateOfBirth();
        }
        return new Eligibility.Person(e.getEmployeeId(), e.getJobCode(), e.getDepartmentCode(), e.getSubsidiaryCode(), hazards, segCodes,
                above, licences, dob);
    }

    public static boolean above(BigDecimal value, BigDecimal actionLevel) {
        return value != null && actionLevel != null && value.compareTo(actionLevel) >= 0;
    }
}
