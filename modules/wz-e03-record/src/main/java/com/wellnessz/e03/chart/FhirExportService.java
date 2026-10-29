package com.wellnessz.e03.chart;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.hl7.fhir.r4.model.AllergyIntolerance;
import org.hl7.fhir.r4.model.Attachment;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.Immunization;
import org.hl7.fhir.r4.model.MedicationStatement;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.StringType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.e03.model.Allergy;
import com.wellnessz.e03.model.AllergyRepository;
import com.wellnessz.e03.model.ClinicalEncounter;
import com.wellnessz.e03.model.ClinicalEncounterRepository;
import com.wellnessz.e03.model.Immunisation;
import com.wellnessz.e03.model.ImmunisationRepository;
import com.wellnessz.e03.model.Medication;
import com.wellnessz.e03.model.MedicationRepository;
import com.wellnessz.e03.model.Problem;
import com.wellnessz.e03.model.ProblemRepository;
import com.wellnessz.e03.model.Result;
import com.wellnessz.e03.model.ResultRepository;
import com.wellnessz.e03.support.RecordGuard;
import com.wellnessz.integration.terminology.CodeSystem;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

import ca.uhn.fhir.context.FhirContext;

/**
 * FHIR R4 mapping of the OH record (E03-BL-001): Patient, Condition, AllergyIntolerance, MedicationStatement, Immunization,
 * Observation, DocumentReference and Encounter; and the HL7 ODH view of work information (PastOrPresentJob, UsualWork,
 * exposures). Restricted-class items are left out for callers without restricted access. Exports are audited.
 */
@Service
public class FhirExportService {

    static final String EMPLOYEE_ID_SYSTEM = "urn:wellnessz:employee-id";
    static final String LOCAL_SYSTEM = "urn:wellnessz:local";
    static final String HAZARD_SYSTEM = "urn:wellnessz:hazard";
    static final String ODH_PAST_OR_PRESENT_JOB = "http://hl7.org/fhir/us/odh/StructureDefinition/odh-PastOrPresentJob";
    static final String ODH_USUAL_WORK = "http://hl7.org/fhir/us/odh/StructureDefinition/odh-UsualWork";

    private final FhirContext fhir;
    private final RecordGuard guard;
    private final ProblemRepository problems;
    private final AllergyRepository allergies;
    private final MedicationRepository medications;
    private final ImmunisationRepository immunisations;
    private final ResultRepository results;
    private final ClinicalEncounterRepository encounters;
    private final ChartService chart;
    private final JobHistoryService jobs;
    private final AuditService audit;

    public FhirExportService(FhirContext fhir, RecordGuard guard, ProblemRepository problems, AllergyRepository allergies,
                             MedicationRepository medications, ImmunisationRepository immunisations, ResultRepository results,
                             ClinicalEncounterRepository encounters, ChartService chart, JobHistoryService jobs, AuditService audit) {
        this.fhir = fhir;
        this.guard = guard;
        this.problems = problems;
        this.allergies = allergies;
        this.medications = medications;
        this.immunisations = immunisations;
        this.results = results;
        this.encounters = encounters;
        this.chart = chart;
        this.jobs = jobs;
        this.audit = audit;
    }

    static String uri(String system) {
        if (system == null) {
            return LOCAL_SYSTEM;
        }
        try {
            return CodeSystem.valueOf(system).uri();
        } catch (IllegalArgumentException e) {
            return LOCAL_SYSTEM;
        }
    }

    static CodeableConcept concept(String system, String code, String display) {
        CodeableConcept c = new CodeableConcept();
        if (code != null) {
            c.addCoding(new Coding(uri(system), code, display));
        }
        c.setText(display);
        return c;
    }

    static CodeableConcept text(String text) {
        return new CodeableConcept().setText(text);
    }

    static Date date(LocalDate d) {
        return d == null ? null : Date.from(d.atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    private static void add(Bundle b, Resource r) {
        b.addEntry().setFullUrl("urn:uuid:" + r.getIdElement().getIdPart()).setResource(r);
    }

    private static Patient patient(Employee e) {
        Patient p = new Patient();
        p.setId(UUID.nameUUIDFromBytes(("patient:" + e.getEmployeeId()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
        p.addIdentifier().setSystem(EMPLOYEE_ID_SYSTEM).setValue(e.getEmployeeId());
        p.addName().setText(e.getDisplayName());
        p.setActive(e.isActive());
        return p;
    }

    // ------------------------------------------------------------------ OH record bundle

    @Transactional(readOnly = true)
    public String record(CurrentUser user, String employeeId) {
        RecordGuard.Access a = guard.read(user, employeeId, "FHIR_EXPORT");
        Employee e = a.employee();
        Bundle b = new Bundle().setType(Bundle.BundleType.COLLECTION);
        Patient patient = patient(e);
        add(b, patient);
        Reference subject = new Reference("urn:uuid:" + patient.getIdElement().getIdPart());
        for (Problem p : problems.findByEmployeeIdOrderByRecordedAtDesc(employeeId)) {
            if (!a.canSee(p.getClassification())) {
                continue;
            }
            Condition c = new Condition();
            c.setId(p.getId().toString());
            String clinical = switch (p.getStatus()) {
                case "RESOLVED" -> "resolved";
                case "INACTIVE" -> "inactive";
                default -> "active";
            };
            c.setClinicalStatus(new CodeableConcept(new Coding("http://terminology.hl7.org/CodeSystem/condition-clinical", clinical, null)));
            c.setVerificationStatus(new CodeableConcept(new Coding("http://terminology.hl7.org/CodeSystem/condition-ver-status", "confirmed", null)));
            c.setCode(concept(p.getCodeSystem(), p.getCode(), p.getCodeDisplay()));
            c.setSubject(subject);
            if (p.getOnsetDate() != null) {
                c.setOnset(new DateTimeType(date(p.getOnsetDate())));
            }
            if (p.getResolvedDate() != null) {
                c.setAbatement(new DateTimeType(date(p.getResolvedDate())));
            }
            c.setRecordedDate(Date.from(p.getRecordedAt()));
            add(b, c);
        }
        for (Allergy x : allergies.findByEmployeeIdOrderByRecordedAtDesc(employeeId)) {
            AllergyIntolerance ai = new AllergyIntolerance();
            ai.setId(x.getId().toString());
            ai.setClinicalStatus(new CodeableConcept(new Coding("http://terminology.hl7.org/CodeSystem/allergyintolerance-clinical",
                    "ACTIVE".equals(x.getStatus()) ? "active" : "inactive", null)));
            ai.setVerificationStatus(new CodeableConcept(new Coding("http://terminology.hl7.org/CodeSystem/allergyintolerance-verification",
                    "ENTERED_IN_ERROR".equals(x.getStatus()) ? "entered-in-error" : "confirmed", null)));
            ai.addCategory(AllergyIntolerance.AllergyIntoleranceCategory.valueOf(x.getCategory()));
            ai.setCriticality("UNABLE_TO_ASSESS".equals(x.getCriticality()) ? AllergyIntolerance.AllergyIntoleranceCriticality.UNABLETOASSESS
                    : AllergyIntolerance.AllergyIntoleranceCriticality.valueOf(x.getCriticality()));
            ai.setCode(concept(x.getSubstanceSystem(), x.getSubstanceCode(), x.getSubstanceDisplay()));
            ai.setPatient(subject);
            if (x.getReaction() != null) {
                ai.addReaction().addManifestation(text(x.getReaction()));
            }
            ai.setRecordedDate(Date.from(x.getRecordedAt()));
            add(b, ai);
        }
        for (Medication m : medications.findByEmployeeIdOrderByRecordedAtDesc(employeeId)) {
            if (!a.canSee(m.getClassification())) {
                continue;
            }
            MedicationStatement ms = new MedicationStatement();
            ms.setId(m.getId().toString());
            ms.setStatus(switch (m.getStatus()) {
                case "COMPLETED" -> MedicationStatement.MedicationStatementStatus.COMPLETED;
                case "STOPPED" -> MedicationStatement.MedicationStatementStatus.STOPPED;
                default -> MedicationStatement.MedicationStatementStatus.ACTIVE;
            });
            CodeableConcept med = m.getCode() == null ? text(m.getName()) : concept(m.getCodeSystem(), m.getCode(), m.getCodeDisplay());
            med.setText(m.getName());
            ms.setMedication(med);
            ms.setSubject(subject);
            ms.setEffective(new Period().setStart(date(m.getStartDate())).setEnd(date(m.getEndDate())));
            String dosage = String.join(" ", java.util.stream.Stream.of(m.getDose(), m.getRoute(), m.getFrequency())
                    .filter(java.util.Objects::nonNull).toList());
            if (!dosage.isBlank()) {
                ms.addDosage().setText(dosage);
            }
            add(b, ms);
        }
        for (Immunisation i : immunisations.findByEmployeeIdOrderByAdministeredOnDesc(employeeId)) {
            Immunization im = new Immunization();
            im.setId(i.getId().toString());
            im.setStatus("DUE".equals(i.getStatus()) ? Immunization.ImmunizationStatus.NOTDONE : Immunization.ImmunizationStatus.COMPLETED);
            CodeableConcept vaccine = i.getCode() == null ? text(i.getVaccine()) : concept(i.getCodeSystem(), i.getCode(), i.getVaccine());
            im.setVaccineCode(vaccine);
            im.setPatient(subject);
            im.setOccurrence(new DateTimeType(date(i.getAdministeredOn())));
            im.setLotNumber(i.getLotNumber());
            if (i.getDoseNumber() != null) {
                im.addProtocolApplied().setDoseNumber(new org.hl7.fhir.r4.model.PositiveIntType(i.getDoseNumber()));
            }
            add(b, im);
        }
        for (Result r : results.findByEmployeeIdOrderByEffectiveAtDesc(employeeId)) {
            if (!a.canSee(r.getClassification())) {
                continue;
            }
            add(b, observation(r, subject));
        }
        for (ClinicalEncounter x : encounters.findByEmployeeIdOrderByCreatedAtDesc(employeeId)) {
            if (!a.canSee(x.getClassification())) {
                continue;
            }
            org.hl7.fhir.r4.model.Encounter en = new org.hl7.fhir.r4.model.Encounter();
            en.setId(x.getId().toString());
            en.setStatus(switch (x.getStatus()) {
                case "SIGNED" -> org.hl7.fhir.r4.model.Encounter.EncounterStatus.FINISHED;
                case "ENTERED_IN_ERROR" -> org.hl7.fhir.r4.model.Encounter.EncounterStatus.ENTEREDINERROR;
                default -> org.hl7.fhir.r4.model.Encounter.EncounterStatus.INPROGRESS;
            });
            en.setClass_(new Coding("http://terminology.hl7.org/CodeSystem/v3-ActCode", "AMB", "ambulatory"));
            en.addType(new CodeableConcept(new Coding(LOCAL_SYSTEM, x.getType(), x.getTitle())));
            en.setSubject(subject);
            en.setPeriod(new Period().setStart(Date.from(x.getCreatedAt())).setEnd(x.getSignedAt() == null ? null : Date.from(x.getSignedAt())));
            if (x.getPrimaryDiagnosisCode() != null) {
                en.addReasonCode(concept(x.getPrimaryDiagnosisSystem(), x.getPrimaryDiagnosisCode(), x.getPrimaryDiagnosisDisplay()));
            }
            add(b, en);
        }
        for (ChartService.DocumentMeta d : chart.documents(employeeId)) {
            if (!a.canSee(d.classification())) {
                continue;
            }
            DocumentReference dr = new DocumentReference();
            dr.setId(d.id().toString());
            dr.setStatus(Enumerations.DocumentReferenceStatus.CURRENT);
            dr.setType(new CodeableConcept(new Coding(LOCAL_SYSTEM, d.type(), d.title())));
            dr.setSubject(subject);
            dr.setDate(Date.from(d.createdAt()));
            dr.setDescription(d.title());
            dr.addContent().setAttachment(new Attachment().setUrl("/api/v1/documents/" + d.id() + "/content").setTitle(d.title()));
            add(b, dr);
        }
        audit.record("FHIR_EXPORT", "EMPLOYEE", employeeId, AuditService.Outcome.SUCCESS, "entries=" + b.getEntry().size());
        return fhir.newJsonParser().encodeResourceToString(b);
    }

    static Observation observation(Result r, Reference subject) {
        Observation o = new Observation();
        o.setId(r.getId().toString());
        o.setStatus(switch (r.getStatus()) {
            case "PRELIMINARY" -> Observation.ObservationStatus.PRELIMINARY;
            case "AMENDED" -> Observation.ObservationStatus.AMENDED;
            default -> Observation.ObservationStatus.FINAL;
        });
        String category = "LAB".equals(r.getCategory()) ? "laboratory" : "IMAGING".equals(r.getCategory()) ? "imaging" : "exam";
        o.addCategory(new CodeableConcept(new Coding("http://terminology.hl7.org/CodeSystem/observation-category", category, null)));
        o.setCode(concept(r.getCodeSystem(), r.getCode(), r.getCodeDisplay()));
        o.setSubject(subject);
        o.setEffective(new DateTimeType(Date.from(r.getEffectiveAt())));
        Double numeric = com.wellnessz.e03.results.ResultFlags.numeric(r.getValue());
        if (numeric != null && r.getUnit() != null) {
            o.setValue(new Quantity().setValue(numeric).setUnit(r.getUnit()));
        } else if (r.getValue() != null) {
            o.setValue(new StringType(r.getValue()));
        }
        String interpretation = switch (r.getFlag()) {
            case "LOW" -> "L";
            case "HIGH" -> "H";
            case "ABNORMAL" -> "A";
            case "CRITICAL" -> "AA";
            default -> "N";
        };
        o.addInterpretation(new CodeableConcept(new Coding("http://terminology.hl7.org/CodeSystem/v3-ObservationInterpretation",
                interpretation, null)));
        if (r.getReferenceRange() != null) {
            o.addReferenceRange().setText(r.getReferenceRange());
        }
        if (r.getCalibrationId() != null) {
            o.setDevice(new Reference().setDisplay((r.getDeviceModel() == null ? "Device" : r.getDeviceModel()) + " " + r.getDeviceId()
                    + " (calibration " + r.getCalibrationId() + ")"));
        }
        return o;
    }

    // ------------------------------------------------------------------ ODH bundle

    @Transactional
    public String odh(CurrentUser user, String employeeId) {
        RecordGuard.Access a = guard.read(user, employeeId, "ODH_EXPORT");
        Employee e = a.employee();
        Bundle b = new Bundle().setType(Bundle.BundleType.COLLECTION);
        Patient patient = patient(e);
        add(b, patient);
        Reference subject = new Reference("urn:uuid:" + patient.getIdElement().getIdPart());
        List<JobHistoryService.JobView> views = jobs.views(jobs.sync(e));
        for (JobHistoryService.JobView j : views) {
            Observation o = new Observation();
            o.setId(j.id().toString());
            o.getMeta().addProfile(ODH_PAST_OR_PRESENT_JOB);
            o.setStatus(Observation.ObservationStatus.FINAL);
            o.setCode(new CodeableConcept(new Coding(CodeSystem.LOINC.uri(), "11341-5", "History of Occupation")));
            o.setSubject(subject);
            o.setEffective(new Period().setStart(date(j.startDate())).setEnd(date(j.endDate())));
            o.setValue(job(j));
            if (j.industry() != null) {
                o.addComponent().setCode(text("Industry")).setValue(text(j.industry()));
            }
            if (j.employer() != null) {
                o.addComponent().setCode(text("Employer")).setValue(new StringType(j.employer()));
            }
            if (j.duties() != null) {
                o.addComponent().setCode(text("Job duties")).setValue(new StringType(j.duties()));
            }
            if (j.weeklyHours() != null) {
                o.addComponent().setCode(text("Work hours per week")).setValue(new Quantity().setValue(j.weeklyHours()).setUnit("h/wk"));
            }
            for (JobHistoryService.HazardView h : j.hazards()) {
                o.addComponent().setCode(text("Occupational exposure"))
                        .setValue(new CodeableConcept(new Coding(HAZARD_SYSTEM, h.code(), h.name())).setText(h.name()));
            }
            add(b, o);
            if (j.current()) {
                Observation usual = new Observation();
                usual.setId(UUID.nameUUIDFromBytes(("usual:" + j.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
                usual.getMeta().addProfile(ODH_USUAL_WORK);
                usual.setStatus(Observation.ObservationStatus.FINAL);
                usual.setCode(new CodeableConcept(new Coding(CodeSystem.LOINC.uri(), "21843-8", "History of Usual occupation")));
                usual.setSubject(subject);
                usual.setValue(job(j));
                if (j.industry() != null) {
                    usual.addComponent().setCode(text("Usual industry")).setValue(text(j.industry()));
                }
                add(b, usual);
            }
        }
        audit.record("ODH_EXPORT", "EMPLOYEE", employeeId, AuditService.Outcome.SUCCESS, "jobs=" + views.size());
        return fhir.newJsonParser().encodeResourceToString(b);
    }

    private static CodeableConcept job(JobHistoryService.JobView j) {
        CodeableConcept c = new CodeableConcept().setText(j.jobTitle());
        if (j.jobCode() != null) {
            c.addCoding(new Coding("urn:wellnessz:job-code", j.jobCode(), j.jobTitle()));
        }
        return c;
    }
}
