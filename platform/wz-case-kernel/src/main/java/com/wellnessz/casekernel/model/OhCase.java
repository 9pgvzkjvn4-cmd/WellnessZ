package com.wellnessz.casekernel.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.wellnessz.casekernel.model.CaseEnums.Intensity;
import com.wellnessz.casekernel.model.CaseEnums.Recordability;
import com.wellnessz.casekernel.model.CaseEnums.WaitingState;
import com.wellnessz.casekernel.model.CaseEnums.WorkRelated;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.secrets.EncryptedStringConverter;

/**
 * OH case aggregate shared by all modules. Identity fields (E01-HLR-05) come from the HRIS employee master;
 * routing/triage fields (urgency, owner, queue, waiting state, intensity) are maintained by E02.
 * Clinical narrative and intake details are encrypted at field level.
 */
@Entity
@Table(name = "oh_case", schema = "casemgmt")
public class OhCase {

    @Id
    private UUID id;
    @Column(name = "case_ref", nullable = false, unique = true, length = 30)
    private String caseRef;
    @Column(name = "employee_id", nullable = false, length = 64)
    private String employeeId;
    @Column(name = "employee_display_name", length = 200)
    private String employeeDisplayName;
    @Column(name = "org_unit_code", length = 64)
    private String orgUnitCode;
    @Column(name = "org_unit_path", length = 1000)
    private String orgUnitPath;
    @Column(name = "subsidiary_code", length = 20)
    private String subsidiaryCode;
    @Column(name = "cost_centre", length = 40)
    private String costCentre;
    @Column(name = "job_code", length = 64)
    private String jobCode;
    @Column(name = "location_code", length = 40)
    private String locationCode;
    @Column(length = 40)
    private String shift;
    @Column(name = "hris_override", nullable = false)
    private boolean hrisOverride;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private CaseCategory category;
    @Enumerated(EnumType.STRING)
    @Column(name = "case_type", nullable = false, length = 40)
    private CaseType caseType;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private CaseSource source;
    @Column(name = "source_ref", length = 200)
    private String sourceRef;
    @Column(name = "incident_ref", length = 100)
    private String incidentRef;
    @Column(name = "body_part", length = 60)
    private String bodyPart;
    @Column(name = "occurred_on")
    private LocalDate occurredOn;
    @Column(length = 8)
    private String language;
    @Column(name = "template_code", length = 40)
    private String templateCode;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private CaseStatus status;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private DataClassification classification;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Urgency urgency;
    @Enumerated(EnumType.STRING)
    @Column(name = "safety_category", length = 40)
    private SafetyCategory safetyCategory;
    @Column(name = "owner_username", length = 200)
    private String ownerUsername;
    @Column(name = "queue_code", length = 40)
    private String queueCode;
    @Enumerated(EnumType.STRING)
    @Column(name = "waiting_state", nullable = false, length = 30)
    private WaitingState waitingState = WaitingState.NONE;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Intensity intensity = Intensity.STANDARD;
    @Column(name = "complexity_score")
    private Integer complexityScore;
    @Column(name = "early_intervention", nullable = false)
    private boolean earlyIntervention;
    @Column(name = "duplicate_suspect", nullable = false)
    private boolean duplicateSuspect;
    @Column(nullable = false)
    private boolean escalated;
    @Column(name = "merged_into_case_id")
    private UUID mergedIntoCaseId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Recordability recordability = Recordability.NOT_CLASSIFIED;
    @Enumerated(EnumType.STRING)
    @Column(name = "work_related", length = 20)
    private WorkRelated workRelated;
    @Enumerated(EnumType.STRING)
    @Column(name = "fitness_status", nullable = false, length = 40)
    private FitnessStatus fitnessStatus;
    @Column(name = "restriction_until")
    private LocalDate restrictionUntil;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "clinical_summary", columnDefinition = "text")
    private String clinicalSummary;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "intake_details", columnDefinition = "text")
    private String intakeDetails;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    protected OhCase() {
    }

    /** Opens a case; identity fields are supplied by {@code CaseService} from the employee master. */
    public OhCase(String caseRef, OpenCaseCommand cmd, CaseIdentity identity, Instant now) {
        this.id = UUID.randomUUID();
        this.caseRef = caseRef;
        this.employeeId = cmd.employeeId();
        this.employeeDisplayName = identity.displayName() != null ? identity.displayName() : cmd.employeeName();
        this.orgUnitCode = identity.departmentCode();
        this.orgUnitPath = identity.orgUnitPath();
        this.subsidiaryCode = identity.subsidiaryCode();
        this.costCentre = identity.costCentre();
        this.jobCode = identity.jobCode();
        this.locationCode = cmd.locationCode() != null ? cmd.locationCode() : identity.locationCode();
        this.shift = cmd.shift() != null ? cmd.shift() : identity.shift();
        this.hrisOverride = identity.hrisOverride();
        this.safetyCategory = identity.safetyCategory();
        this.category = cmd.category();
        this.caseType = cmd.caseType() != null ? cmd.caseType() : CaseType.defaultFor(cmd.category());
        this.classification = category.classification();
        this.source = cmd.source();
        this.sourceRef = cmd.sourceRef();
        this.incidentRef = cmd.incidentRef();
        this.bodyPart = cmd.bodyPart();
        this.occurredOn = cmd.occurredOn();
        this.language = cmd.language() != null ? cmd.language() : identity.language();
        this.templateCode = cmd.templateCode();
        this.clinicalSummary = cmd.clinicalSummary();
        this.intakeDetails = cmd.intakeDetailsJson();
        this.urgency = cmd.urgency() != null ? cmd.urgency() : Urgency.ROUTINE;
        this.earlyIntervention = cmd.earlyIntervention();
        this.status = CaseStatus.INTAKE;
        this.fitnessStatus = cmd.initialFitness() != null ? cmd.initialFitness() : FitnessStatus.NOT_ASSESSED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void transition(CaseStatus next, Instant now) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("Transition " + status + " -> " + next + " not allowed");
        }
        this.status = next;
        this.updatedAt = now;
    }

    public void recordFitness(FitnessStatus fitness, LocalDate until, Instant now) {
        this.fitnessStatus = fitness;
        this.restrictionUntil = until;
        this.updatedAt = now;
    }

    public void route(String queueCode, String ownerUsername, Instant now) {
        this.queueCode = queueCode;
        this.ownerUsername = ownerUsername;
        this.updatedAt = now;
    }

    public void triage(Urgency urgency, CaseType caseType, Instant now) {
        this.urgency = urgency;
        if (caseType != null) {
            this.caseType = caseType;
        }
        this.updatedAt = now;
    }

    public void raiseUrgencyTo(Urgency minimum, Instant now) {
        Urgency raised = Urgency.max(urgency, minimum);
        if (raised != urgency) {
            this.urgency = raised;
            this.updatedAt = now;
        }
    }

    public void setWaitingState(WaitingState state, Instant now) {
        this.waitingState = state;
        this.updatedAt = now;
    }

    public void setComplexity(int score, Intensity intensity, Instant now) {
        this.complexityScore = score;
        this.intensity = intensity;
        this.updatedAt = now;
    }

    public void classify(Recordability recordability, WorkRelated workRelated, Instant now) {
        this.recordability = recordability;
        this.workRelated = workRelated;
        this.updatedAt = now;
    }

    public void flagDuplicateSuspect(boolean suspect, Instant now) {
        this.duplicateSuspect = suspect;
        this.updatedAt = now;
    }

    public void setEscalated(boolean escalated, Instant now) {
        this.escalated = escalated;
        this.updatedAt = now;
    }

    public void mergeInto(UUID target, Instant now) {
        transition(CaseStatus.MERGED, now);
        this.mergedIntoCaseId = target;
    }

    public void appendClinicalSummary(String text, Instant now) {
        this.clinicalSummary = clinicalSummary == null || clinicalSummary.isBlank() ? text : clinicalSummary + "\n---\n" + text;
        this.updatedAt = now;
    }

    public boolean isSafetyCritical() {
        return safetyCategory != null;
    }

    public boolean isOpen() {
        return status != CaseStatus.CLOSED && status != CaseStatus.MERGED;
    }

    public UUID getId() {
        return id;
    }

    public String getCaseRef() {
        return caseRef;
    }

    public String getEmployeeId() {
        return employeeId;
    }

    public String getEmployeeDisplayName() {
        return employeeDisplayName;
    }

    public String getOrgUnitCode() {
        return orgUnitCode;
    }

    public String getOrgUnitPath() {
        return orgUnitPath;
    }

    public String getSubsidiaryCode() {
        return subsidiaryCode;
    }

    public String getCostCentre() {
        return costCentre;
    }

    public String getJobCode() {
        return jobCode;
    }

    public String getLocationCode() {
        return locationCode;
    }

    public String getShift() {
        return shift;
    }

    public boolean isHrisOverride() {
        return hrisOverride;
    }

    public CaseCategory getCategory() {
        return category;
    }

    public CaseType getCaseType() {
        return caseType;
    }

    public CaseSource getSource() {
        return source;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public String getIncidentRef() {
        return incidentRef;
    }

    public String getBodyPart() {
        return bodyPart;
    }

    public LocalDate getOccurredOn() {
        return occurredOn;
    }

    public String getLanguage() {
        return language;
    }

    public String getTemplateCode() {
        return templateCode;
    }

    public CaseStatus getStatus() {
        return status;
    }

    public DataClassification getClassification() {
        return classification;
    }

    public Urgency getUrgency() {
        return urgency;
    }

    public SafetyCategory getSafetyCategory() {
        return safetyCategory;
    }

    public String getOwnerUsername() {
        return ownerUsername;
    }

    public String getQueueCode() {
        return queueCode;
    }

    public WaitingState getWaitingState() {
        return waitingState;
    }

    public Intensity getIntensity() {
        return intensity;
    }

    public Integer getComplexityScore() {
        return complexityScore;
    }

    public boolean isEarlyIntervention() {
        return earlyIntervention;
    }

    public boolean isDuplicateSuspect() {
        return duplicateSuspect;
    }

    public boolean isEscalated() {
        return escalated;
    }

    public UUID getMergedIntoCaseId() {
        return mergedIntoCaseId;
    }

    public Recordability getRecordability() {
        return recordability;
    }

    public WorkRelated getWorkRelated() {
        return workRelated;
    }

    public FitnessStatus getFitnessStatus() {
        return fitnessStatus;
    }

    public LocalDate getRestrictionUntil() {
        return restrictionUntil;
    }

    public String getClinicalSummary() {
        return clinicalSummary;
    }

    public String getIntakeDetails() {
        return intakeDetails;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
