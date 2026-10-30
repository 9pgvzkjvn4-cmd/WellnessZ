package com.wellnessz.e10.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.wellnessz.secrets.EncryptedStringConverter;

/** One test requirement (any reason) from requirement to verified outcome. Results and notes are encrypted. */
@Entity(name = "E10Test")
@Table(name = "test", schema = "substance")
public class DaTest {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "ref")
    private String ref;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "role_group")
    private String roleGroup;
    @Column(name = "reason")
    private String reason;
    @Column(name = "panels")
    private String panels;
    @Column(name = "status")
    private String status;
    @Column(name = "source_ref")
    private String sourceRef;
    @Column(name = "selection_id")
    private UUID selectionId;
    @Column(name = "incident_ref")
    private String incidentRef;
    @Column(name = "rtd_plan_id")
    private UUID rtdPlanId;
    @Column(name = "scheduled_for")
    private LocalDate scheduledFor;
    @Column(name = "due_at")
    private Instant dueAt;
    @Column(name = "alcohol_due_at")
    private Instant alcoholDueAt;
    @Column(name = "drug_due_at")
    private Instant drugDueAt;
    @Column(name = "notified_at")
    private Instant notifiedAt;
    @Column(name = "dispatched_at")
    private Instant dispatchedAt;
    @Column(name = "collected_at")
    private Instant collectedAt;
    @Column(name = "alcohol_collected_at")
    private Instant alcoholCollectedAt;
    @Column(name = "site_code")
    private String siteCode;
    @Column(name = "observed_at")
    private Instant observedAt;
    @Column(name = "observations")
    private String observations;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "description", columnDefinition = "text")
    private String description;
    @Column(name = "second_observer_name")
    private String secondObserverName;
    @Column(name = "location")
    private String location;
    @Column(name = "requested_by")
    private String requestedBy;
    @Column(name = "refusal_reason")
    private String refusalReason;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "refusal_observations", columnDefinition = "text")
    private String refusalObservations;
    @Column(name = "lab_ref")
    private String labRef;
    @Column(name = "lab_validity")
    private String labValidity;
    @Column(name = "lab_received_at")
    private Instant labReceivedAt;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "results", columnDefinition = "text")
    private String results;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "breath_result", columnDefinition = "text")
    private String breathResult;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "split_results", columnDefinition = "text")
    private String splitResults;
    @Column(name = "split_requested_at")
    private Instant splitRequestedAt;
    @Column(name = "verdict")
    private String verdict;
    @Column(name = "prescription_verified")
    private Boolean prescriptionVerified;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "interview_note", columnDefinition = "text")
    private String interviewNote;
    @Column(name = "mro_username")
    private String mroUsername;
    @Column(name = "mro_decided_at")
    private Instant mroDecidedAt;
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "support_case_id")
    private UUID supportCaseId;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "updated_at")
    private Instant updatedAt;
    @Version
    @Column(name = "version")
    private Long version;

    public DaTest() {
        // JPA; populated through setters by the owning service
    }

    public static DaTest create() {
        DaTest x = new DaTest();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getRef() { return ref; }
    public void setRef(String v) { this.ref = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public String getRoleGroup() { return roleGroup; }
    public void setRoleGroup(String v) { this.roleGroup = v; }
    public String getReason() { return reason; }
    public void setReason(String v) { this.reason = v; }
    public String getPanels() { return panels; }
    public void setPanels(String v) { this.panels = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getSourceRef() { return sourceRef; }
    public void setSourceRef(String v) { this.sourceRef = v; }
    public UUID getSelectionId() { return selectionId; }
    public void setSelectionId(UUID v) { this.selectionId = v; }
    public String getIncidentRef() { return incidentRef; }
    public void setIncidentRef(String v) { this.incidentRef = v; }
    public UUID getRtdPlanId() { return rtdPlanId; }
    public void setRtdPlanId(UUID v) { this.rtdPlanId = v; }
    public LocalDate getScheduledFor() { return scheduledFor; }
    public void setScheduledFor(LocalDate v) { this.scheduledFor = v; }
    public Instant getDueAt() { return dueAt; }
    public void setDueAt(Instant v) { this.dueAt = v; }
    public Instant getAlcoholDueAt() { return alcoholDueAt; }
    public void setAlcoholDueAt(Instant v) { this.alcoholDueAt = v; }
    public Instant getDrugDueAt() { return drugDueAt; }
    public void setDrugDueAt(Instant v) { this.drugDueAt = v; }
    public Instant getNotifiedAt() { return notifiedAt; }
    public void setNotifiedAt(Instant v) { this.notifiedAt = v; }
    public Instant getDispatchedAt() { return dispatchedAt; }
    public void setDispatchedAt(Instant v) { this.dispatchedAt = v; }
    public Instant getCollectedAt() { return collectedAt; }
    public void setCollectedAt(Instant v) { this.collectedAt = v; }
    public Instant getAlcoholCollectedAt() { return alcoholCollectedAt; }
    public void setAlcoholCollectedAt(Instant v) { this.alcoholCollectedAt = v; }
    public String getSiteCode() { return siteCode; }
    public void setSiteCode(String v) { this.siteCode = v; }
    public Instant getObservedAt() { return observedAt; }
    public void setObservedAt(Instant v) { this.observedAt = v; }
    public String getObservations() { return observations; }
    public void setObservations(String v) { this.observations = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public String getSecondObserverName() { return secondObserverName; }
    public void setSecondObserverName(String v) { this.secondObserverName = v; }
    public String getLocation() { return location; }
    public void setLocation(String v) { this.location = v; }
    public String getRequestedBy() { return requestedBy; }
    public void setRequestedBy(String v) { this.requestedBy = v; }
    public String getRefusalReason() { return refusalReason; }
    public void setRefusalReason(String v) { this.refusalReason = v; }
    public String getRefusalObservations() { return refusalObservations; }
    public void setRefusalObservations(String v) { this.refusalObservations = v; }
    public String getLabRef() { return labRef; }
    public void setLabRef(String v) { this.labRef = v; }
    public String getLabValidity() { return labValidity; }
    public void setLabValidity(String v) { this.labValidity = v; }
    public Instant getLabReceivedAt() { return labReceivedAt; }
    public void setLabReceivedAt(Instant v) { this.labReceivedAt = v; }
    public String getResults() { return results; }
    public void setResults(String v) { this.results = v; }
    public String getBreathResult() { return breathResult; }
    public void setBreathResult(String v) { this.breathResult = v; }
    public String getSplitResults() { return splitResults; }
    public void setSplitResults(String v) { this.splitResults = v; }
    public Instant getSplitRequestedAt() { return splitRequestedAt; }
    public void setSplitRequestedAt(Instant v) { this.splitRequestedAt = v; }
    public String getVerdict() { return verdict; }
    public void setVerdict(String v) { this.verdict = v; }
    public Boolean getPrescriptionVerified() { return prescriptionVerified; }
    public void setPrescriptionVerified(Boolean v) { this.prescriptionVerified = v; }
    public String getInterviewNote() { return interviewNote; }
    public void setInterviewNote(String v) { this.interviewNote = v; }
    public String getMroUsername() { return mroUsername; }
    public void setMroUsername(String v) { this.mroUsername = v; }
    public Instant getMroDecidedAt() { return mroDecidedAt; }
    public void setMroDecidedAt(Instant v) { this.mroDecidedAt = v; }
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID v) { this.caseId = v; }
    public UUID getSupportCaseId() { return supportCaseId; }
    public void setSupportCaseId(UUID v) { this.supportCaseId = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
    public Long getVersion() { return version; }
}
