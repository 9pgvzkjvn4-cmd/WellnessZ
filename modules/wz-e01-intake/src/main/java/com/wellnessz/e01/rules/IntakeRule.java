package com.wellnessz.e01.rules;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.wellnessz.casekernel.model.CaseType;
import com.wellnessz.casekernel.model.Urgency;

/**
 * Admin-editable incident-to-case rule (E01-BL-003). Null conditions are wildcards; the active rule with the
 * lowest priority number that matches wins.
 */
@Entity
@Table(name = "intake_rule", schema = "intake")
public class IntakeRule {

    static final List<String> SEVERITY_ORDER = List.of("LOW", "MEDIUM", "HIGH", "CRITICAL");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 200)
    private String name;
    @Column(nullable = false)
    private int priority;
    @Column(nullable = false)
    private boolean active = true;
    @Column(name = "incident_class", length = 40)
    private String incidentClass;
    @Column(name = "min_severity", length = 20)
    private String minSeverity;
    @Column(name = "lost_time")
    private Boolean lostTime;
    @Column
    private Boolean injury;
    @Column(name = "exposure_type", length = 60)
    private String exposureType;
    @Column(name = "unit_code", length = 20)
    private String unitCode;
    @Column(name = "body_part", length = 60)
    private String bodyPart;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RuleAction action;
    @Enumerated(EnumType.STRING)
    @Column(name = "case_type", length = 40)
    private CaseType caseType;
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Urgency urgency;
    @Version
    private long version;
    @Column(name = "updated_by", nullable = false, length = 200)
    private String updatedBy;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected IntakeRule() {
    }

    public IntakeRule(String updatedBy, Instant now) {
        this.updatedBy = updatedBy;
        this.updatedAt = now;
    }

    public void update(String name, int priority, boolean active, String incidentClass, String minSeverity, Boolean lostTime,
                       Boolean injury, String exposureType, String unitCode, String bodyPart, RuleAction action,
                       CaseType caseType, Urgency urgency, String actor, Instant now) {
        this.name = name;
        this.priority = priority;
        this.active = active;
        this.incidentClass = incidentClass;
        this.minSeverity = minSeverity;
        this.lostTime = lostTime;
        this.injury = injury;
        this.exposureType = exposureType;
        this.unitCode = unitCode;
        this.bodyPart = bodyPart;
        this.action = action;
        this.caseType = caseType;
        this.urgency = urgency;
        this.updatedBy = actor;
        this.updatedAt = now;
    }

    public boolean matches(IncidentFacts f) {
        return eq(incidentClass, f.incidentClass())
                && eq(exposureType, f.exposureType())
                && eq(unitCode, f.unitCode())
                && eq(bodyPart, f.bodyPart())
                && (lostTime == null || lostTime.equals(f.lostTime()))
                && (injury == null || injury == f.injury())
                && (minSeverity == null || severityRank(f.severity()) >= severityRank(minSeverity));
    }

    private static boolean eq(String condition, String value) {
        return condition == null || (value != null && condition.equalsIgnoreCase(value));
    }

    static int severityRank(String severity) {
        return severity == null ? -1 : SEVERITY_ORDER.indexOf(severity.toUpperCase(Locale.ROOT));
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getPriority() {
        return priority;
    }

    public boolean isActive() {
        return active;
    }

    public String getIncidentClass() {
        return incidentClass;
    }

    public String getMinSeverity() {
        return minSeverity;
    }

    public Boolean getLostTime() {
        return lostTime;
    }

    public Boolean getInjury() {
        return injury;
    }

    public String getExposureType() {
        return exposureType;
    }

    public String getUnitCode() {
        return unitCode;
    }

    public String getBodyPart() {
        return bodyPart;
    }

    public RuleAction getAction() {
        return action;
    }

    public CaseType getCaseType() {
        return caseType;
    }

    public Urgency getUrgency() {
        return urgency;
    }

    public long getVersion() {
        return version;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
