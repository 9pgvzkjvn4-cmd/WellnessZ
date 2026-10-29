package com.wellnessz.e03.web;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.common.model.Roles;
import com.wellnessz.e03.chart.ChartService;
import com.wellnessz.e03.chart.FhirExportService;
import com.wellnessz.e03.chart.JobHistoryService;
import com.wellnessz.e03.chart.RecordService;
import com.wellnessz.security.identity.CurrentUserProvider;

/**
 * Employee OH chart (E03-F01). Reads are decided by ABAC on the employee record (OH team; restricted class for
 * PSYCH_CLINICIAN / break-glass) and denials are audited 404s – HR, managers and employees never see chart content.
 * Writes additionally require a clinician role.
 */
@RestController
@RequestMapping("/api/v1")
public class ChartController {

    static final String EMPLOYEE_ID = "[A-Za-z0-9_\\-]{1,64}";
    static final String FHIR_JSON = "application/fhir+json";

    private final ChartService chart;
    private final RecordService records;
    private final JobHistoryService jobs;
    private final FhirExportService fhir;
    private final CurrentUserProvider users;

    public ChartController(ChartService chart, RecordService records, JobHistoryService jobs, FhirExportService fhir, CurrentUserProvider users) {
        this.chart = chart;
        this.records = records;
        this.jobs = jobs;
        this.fhir = fhir;
        this.users = users;
    }

    @GetMapping("/employees/{employeeId}/chart")
    public ChartService.ChartSummary chart(@PathVariable @Pattern(regexp = EMPLOYEE_ID) String employeeId) {
        return chart.summary(users.require(), employeeId);
    }

    @GetMapping("/employees/{employeeId}/record-timeline")
    public List<ChartService.TimelineEntry> timeline(@PathVariable @Pattern(regexp = EMPLOYEE_ID) String employeeId) {
        return chart.timeline(users.require(), employeeId);
    }

    // ------------------------------------------------------------------ problems

    @GetMapping("/employees/{employeeId}/problems")
    public List<RecordService.ProblemView> problems(@PathVariable @Pattern(regexp = EMPLOYEE_ID) String employeeId) {
        return records.problems(users.require(), employeeId);
    }

    @PostMapping("/employees/{employeeId}/problems")
    @PreAuthorize(Roles.CLINICIANS_SPEL)
    @ResponseStatus(HttpStatus.CREATED)
    public RecordService.ProblemView addProblem(@PathVariable @Pattern(regexp = EMPLOYEE_ID) String employeeId,
                                                @Valid @RequestBody RecordService.ProblemRequest r) {
        return records.addProblem(users.require(), employeeId, r);
    }

    @PutMapping("/problems/{id}")
    @PreAuthorize(Roles.CLINICIANS_SPEL)
    public RecordService.ProblemView updateProblem(@PathVariable UUID id, @Valid @RequestBody RecordService.ProblemUpdate r) {
        return records.updateProblem(users.require(), id, r);
    }

    // ------------------------------------------------------------------ allergies

    @GetMapping("/employees/{employeeId}/allergies")
    public List<RecordService.AllergyView> allergies(@PathVariable @Pattern(regexp = EMPLOYEE_ID) String employeeId) {
        return records.allergies(users.require(), employeeId);
    }

    @PostMapping("/employees/{employeeId}/allergies")
    @PreAuthorize(Roles.CLINICIANS_SPEL)
    @ResponseStatus(HttpStatus.CREATED)
    public RecordService.AllergyView addAllergy(@PathVariable @Pattern(regexp = EMPLOYEE_ID) String employeeId,
                                                @Valid @RequestBody RecordService.AllergyRequest r) {
        return records.addAllergy(users.require(), employeeId, r);
    }

    @PutMapping("/allergies/{id}")
    @PreAuthorize(Roles.CLINICIANS_SPEL)
    public RecordService.AllergyView updateAllergy(@PathVariable UUID id, @Valid @RequestBody RecordService.AllergyUpdate r) {
        return records.updateAllergy(users.require(), id, r);
    }

    // ------------------------------------------------------------------ medications

    @GetMapping("/employees/{employeeId}/medications")
    public List<RecordService.MedicationView> medications(@PathVariable @Pattern(regexp = EMPLOYEE_ID) String employeeId) {
        return records.medications(users.require(), employeeId);
    }

    @PostMapping("/employees/{employeeId}/medications")
    @PreAuthorize(Roles.CLINICIANS_SPEL)
    @ResponseStatus(HttpStatus.CREATED)
    public RecordService.MedicationView addMedication(@PathVariable @Pattern(regexp = EMPLOYEE_ID) String employeeId,
                                                      @Valid @RequestBody RecordService.MedicationRequest r) {
        return records.addMedication(users.require(), employeeId, r);
    }

    @PutMapping("/medications/{id}")
    @PreAuthorize(Roles.CLINICIANS_SPEL)
    public RecordService.MedicationView updateMedication(@PathVariable UUID id, @Valid @RequestBody RecordService.MedicationUpdate r) {
        return records.updateMedication(users.require(), id, r);
    }

    // ------------------------------------------------------------------ immunisations

    @GetMapping("/employees/{employeeId}/immunisations")
    public List<RecordService.ImmunisationView> immunisations(@PathVariable @Pattern(regexp = EMPLOYEE_ID) String employeeId) {
        return records.immunisations(users.require(), employeeId);
    }

    @PostMapping("/employees/{employeeId}/immunisations")
    @PreAuthorize(Roles.CLINICIANS_SPEL)
    @ResponseStatus(HttpStatus.CREATED)
    public RecordService.ImmunisationView addImmunisation(@PathVariable @Pattern(regexp = EMPLOYEE_ID) String employeeId,
                                                          @Valid @RequestBody RecordService.ImmunisationRequest r) {
        return records.addImmunisation(users.require(), employeeId, r);
    }

    // ------------------------------------------------------------------ ODH job history

    @GetMapping("/employees/{employeeId}/jobs")
    public List<JobHistoryService.JobView> jobs(@PathVariable @Pattern(regexp = EMPLOYEE_ID) String employeeId) {
        return jobs.list(users.require(), employeeId);
    }

    @PostMapping("/employees/{employeeId}/jobs")
    @PreAuthorize(Roles.CLINICIANS_SPEL)
    @ResponseStatus(HttpStatus.CREATED)
    public JobHistoryService.JobView addJob(@PathVariable @Pattern(regexp = EMPLOYEE_ID) String employeeId,
                                            @Valid @RequestBody JobHistoryService.JobRequest r) {
        return jobs.add(users.require(), employeeId, r);
    }

    @PutMapping("/jobs/{id}")
    @PreAuthorize(Roles.CLINICIANS_SPEL)
    public JobHistoryService.JobView updateJob(@PathVariable UUID id, @Valid @RequestBody JobHistoryService.JobRequest r) {
        return jobs.update(users.require(), id, r);
    }

    // ------------------------------------------------------------------ FHIR / ODH

    @GetMapping(value = "/employees/{employeeId}/odh", produces = {FHIR_JSON, "application/json"})
    public String odh(@PathVariable @Pattern(regexp = EMPLOYEE_ID) String employeeId) {
        return fhir.odh(users.require(), employeeId);
    }

    @GetMapping(value = "/employees/{employeeId}/fhir", produces = {FHIR_JSON, "application/json"})
    public String fhirBundle(@PathVariable @Pattern(regexp = EMPLOYEE_ID) String employeeId) {
        return fhir.record(users.require(), employeeId);
    }
}
