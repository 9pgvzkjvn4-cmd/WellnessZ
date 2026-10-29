package com.wellnessz.e01.recordability;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.casekernel.model.CaseEnums.Recordability;
import com.wellnessz.casekernel.model.CaseEnums.WorkRelated;
import com.wellnessz.secrets.EncryptedStringConverter;

/** Versioned recordability decision with answers, rationale and change reason (E01-BL-021). Append-only by design. */
@Entity
@Table(name = "recordability_assessment", schema = "intake")
public class RecordabilityAssessment {

    @Id
    private UUID id;
    @Column(name = "case_id", nullable = false)
    private UUID caseId;
    @Column(nullable = false)
    private int version;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "answers", nullable = false, columnDefinition = "text")
    private String answersJson;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Recordability computed;
    @Enumerated(EnumType.STRING)
    @Column(name = "final_class", nullable = false, length = 30)
    private Recordability finalClass;
    @Enumerated(EnumType.STRING)
    @Column(name = "work_related", nullable = false, length = 20)
    private WorkRelated workRelated;
    @Column(name = "reason_code", length = 40)
    private String reasonCode;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(columnDefinition = "text")
    private String rationale;
    @Column(name = "local_category", length = 100)
    private String localCategory;
    @Column(name = "caa_reportable", nullable = false)
    private boolean caaReportable;
    @Column(name = "assessed_by", nullable = false, length = 200)
    private String assessedBy;
    @Column(name = "assessed_at", nullable = false)
    private Instant assessedAt;

    protected RecordabilityAssessment() {
    }

    public RecordabilityAssessment(UUID caseId, int version, String answersJson, Recordability computed, Recordability finalClass,
                                   WorkRelated workRelated, String reasonCode, String rationale, String localCategory,
                                   boolean caaReportable, String assessedBy, Instant now) {
        this.id = UUID.randomUUID();
        this.caseId = caseId;
        this.version = version;
        this.answersJson = answersJson;
        this.computed = computed;
        this.finalClass = finalClass;
        this.workRelated = workRelated;
        this.reasonCode = reasonCode;
        this.rationale = rationale;
        this.localCategory = localCategory;
        this.caaReportable = caaReportable;
        this.assessedBy = assessedBy;
        this.assessedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public int getVersion() {
        return version;
    }

    public String getAnswersJson() {
        return answersJson;
    }

    public Recordability getComputed() {
        return computed;
    }

    public Recordability getFinalClass() {
        return finalClass;
    }

    public WorkRelated getWorkRelated() {
        return workRelated;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public String getRationale() {
        return rationale;
    }

    public String getLocalCategory() {
        return localCategory;
    }

    public boolean isCaaReportable() {
        return caaReportable;
    }

    public String getAssessedBy() {
        return assessedBy;
    }

    public Instant getAssessedAt() {
        return assessedAt;
    }
}
