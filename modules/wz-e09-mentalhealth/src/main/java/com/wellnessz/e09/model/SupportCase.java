package com.wellnessz.e09.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.wellnessz.secrets.EncryptedStringConverter;

/** Confidential support case (E09-F01): one per episode, backed by a restricted kernel case. Contact phone, message and close reason are encrypted. */
@Entity(name = "E09SupportCase")
@Table(name = "support_case", schema = "mentalhealth")
public class SupportCase {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "case_ref")
    private String caseRef;
    @Column(name = "kernel_case_id")
    private UUID kernelCaseId;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "status")
    private String status;
    @Column(name = "risk")
    private String risk;
    @Column(name = "concern")
    private String concern;
    @Column(name = "source")
    private String source;
    @Column(name = "source_type")
    private String sourceType;
    @Column(name = "source_ref")
    private String sourceRef;
    @Column(name = "preferred_contact")
    private String preferredContact;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "contact_phone", columnDefinition = "text")
    private String contactPhone;
    @Column(name = "best_time")
    private String bestTime;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "message", columnDefinition = "text")
    private String message;
    @Column(name = "next_contact_by")
    private Instant nextContactBy;
    @Column(name = "opened_at")
    private Instant openedAt;
    @Column(name = "opened_by")
    private String openedBy;
    @Column(name = "last_contact_at")
    private Instant lastContactAt;
    @Column(name = "sharing")
    private String sharing;
    @Column(name = "sharing_adjustments")
    private String sharingAdjustments;
    @Column(name = "sharing_changed_at")
    private Instant sharingChangedAt;
    @Column(name = "closed_at")
    private Instant closedAt;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "close_reason", columnDefinition = "text")
    private String closeReason;
    @Column(name = "updated_at")
    private Instant updatedAt;
    @Version
    @Column(name = "version")
    private Long version;

    public SupportCase() {
        // JPA; populated through setters by the owning service
    }

    public static SupportCase create() {
        SupportCase x = new SupportCase();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getCaseRef() { return caseRef; }
    public void setCaseRef(String v) { this.caseRef = v; }
    public UUID getKernelCaseId() { return kernelCaseId; }
    public void setKernelCaseId(UUID v) { this.kernelCaseId = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getRisk() { return risk; }
    public void setRisk(String v) { this.risk = v; }
    public String getConcern() { return concern; }
    public void setConcern(String v) { this.concern = v; }
    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String v) { this.sourceType = v; }
    public String getSourceRef() { return sourceRef; }
    public void setSourceRef(String v) { this.sourceRef = v; }
    public String getPreferredContact() { return preferredContact; }
    public void setPreferredContact(String v) { this.preferredContact = v; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String v) { this.contactPhone = v; }
    public String getBestTime() { return bestTime; }
    public void setBestTime(String v) { this.bestTime = v; }
    public String getMessage() { return message; }
    public void setMessage(String v) { this.message = v; }
    public Instant getNextContactBy() { return nextContactBy; }
    public void setNextContactBy(Instant v) { this.nextContactBy = v; }
    public Instant getOpenedAt() { return openedAt; }
    public void setOpenedAt(Instant v) { this.openedAt = v; }
    public String getOpenedBy() { return openedBy; }
    public void setOpenedBy(String v) { this.openedBy = v; }
    public Instant getLastContactAt() { return lastContactAt; }
    public void setLastContactAt(Instant v) { this.lastContactAt = v; }
    public String getSharing() { return sharing; }
    public void setSharing(String v) { this.sharing = v; }
    public String getSharingAdjustments() { return sharingAdjustments; }
    public void setSharingAdjustments(String v) { this.sharingAdjustments = v; }
    public Instant getSharingChangedAt() { return sharingChangedAt; }
    public void setSharingChangedAt(Instant v) { this.sharingChangedAt = v; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant v) { this.closedAt = v; }
    public String getCloseReason() { return closeReason; }
    public void setCloseReason(String v) { this.closeReason = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
    public Long getVersion() { return version; }
}
