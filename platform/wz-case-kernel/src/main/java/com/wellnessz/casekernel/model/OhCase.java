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

import com.wellnessz.common.model.DataClassification;
import com.wellnessz.secrets.EncryptedStringConverter;

/**
 * Minimal OH case aggregate shared by all modules. Clinical narrative is encrypted at field level;
 * module-specific clinical records (encounters, assessments …) live in their own tables keyed by case id.
 */
@Entity
@Table(name = "oh_case", schema = "casemgmt")
public class OhCase {

    @Id
    private UUID id;
    @Column(name = "case_ref", nullable = false, unique = true, length = 20)
    private String caseRef;
    @Column(name = "employee_id", nullable = false, length = 64)
    private String employeeId;
    @Column(name = "employee_display_name", length = 200)
    private String employeeDisplayName;
    @Column(name = "org_unit_code", length = 64)
    private String orgUnitCode;
    @Column(name = "org_unit_path", length = 1000)
    private String orgUnitPath;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private CaseCategory category;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private CaseSource source;
    @Column(name = "source_ref", length = 200)
    private String sourceRef;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private CaseStatus status;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private DataClassification classification;
    @Enumerated(EnumType.STRING)
    @Column(name = "fitness_status", nullable = false, length = 40)
    private FitnessStatus fitnessStatus;
    @Column(name = "restriction_until")
    private LocalDate restrictionUntil;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "clinical_summary", columnDefinition = "text")
    private String clinicalSummary;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    protected OhCase() {
    }

    public OhCase(String caseRef, String employeeId, String employeeDisplayName, String orgUnitCode, String orgUnitPath,
                  CaseCategory category, CaseSource source, String sourceRef, String clinicalSummary, Instant now) {
        this.id = UUID.randomUUID();
        this.caseRef = caseRef;
        this.employeeId = employeeId;
        this.employeeDisplayName = employeeDisplayName;
        this.orgUnitCode = orgUnitCode;
        this.orgUnitPath = orgUnitPath;
        this.category = category;
        this.classification = category.classification();
        this.source = source;
        this.sourceRef = sourceRef;
        this.clinicalSummary = clinicalSummary;
        this.status = CaseStatus.INTAKE;
        this.fitnessStatus = FitnessStatus.NOT_ASSESSED;
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

    public CaseCategory getCategory() {
        return category;
    }

    public CaseSource getSource() {
        return source;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public CaseStatus getStatus() {
        return status;
    }

    public DataClassification getClassification() {
        return classification;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
