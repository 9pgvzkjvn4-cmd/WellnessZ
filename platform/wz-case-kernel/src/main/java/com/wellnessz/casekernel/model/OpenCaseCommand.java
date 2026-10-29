package com.wellnessz.casekernel.model;

import java.time.LocalDate;

/**
 * Everything an intake channel (E01) knows when it opens a case. Identity fields are looked up from the HRIS
 * employee master by the kernel; {@code employeeName} is only a fallback for employees unknown to HRIS.
 */
public record OpenCaseCommand(String employeeId, String employeeName, String fallbackOrgUnitCode, CaseCategory category,
                              CaseType caseType, CaseSource source, String sourceRef, String incidentRef, String bodyPart,
                              LocalDate occurredOn, String locationCode, String shift, String language, String templateCode,
                              Urgency urgency, String clinicalSummary, String intakeDetailsJson, boolean earlyIntervention,
                              FitnessStatus initialFitness) {

    public static Builder builder(String employeeId, CaseCategory category, CaseSource source) {
        return new Builder(employeeId, category, source);
    }

    /** Fluent builder – most channels set only a handful of fields. */
    public static final class Builder {
        private final String employeeId;
        private final CaseCategory category;
        private final CaseSource source;
        private String employeeName;
        private String fallbackOrgUnitCode;
        private CaseType caseType;
        private String sourceRef;
        private String incidentRef;
        private String bodyPart;
        private LocalDate occurredOn;
        private String locationCode;
        private String shift;
        private String language;
        private String templateCode;
        private Urgency urgency;
        private String clinicalSummary;
        private String intakeDetailsJson;
        private boolean earlyIntervention;
        private FitnessStatus initialFitness;

        private Builder(String employeeId, CaseCategory category, CaseSource source) {
            this.employeeId = employeeId;
            this.category = category;
            this.source = source;
        }

        public Builder employeeName(String v) { this.employeeName = v; return this; }
        public Builder fallbackOrgUnitCode(String v) { this.fallbackOrgUnitCode = v; return this; }
        public Builder caseType(CaseType v) { this.caseType = v; return this; }
        public Builder sourceRef(String v) { this.sourceRef = v; return this; }
        public Builder incidentRef(String v) { this.incidentRef = v; return this; }
        public Builder bodyPart(String v) { this.bodyPart = v; return this; }
        public Builder occurredOn(LocalDate v) { this.occurredOn = v; return this; }
        public Builder locationCode(String v) { this.locationCode = v; return this; }
        public Builder shift(String v) { this.shift = v; return this; }
        public Builder language(String v) { this.language = v; return this; }
        public Builder templateCode(String v) { this.templateCode = v; return this; }
        public Builder urgency(Urgency v) { this.urgency = v; return this; }
        public Builder clinicalSummary(String v) { this.clinicalSummary = v; return this; }
        public Builder intakeDetailsJson(String v) { this.intakeDetailsJson = v; return this; }
        public Builder earlyIntervention(boolean v) { this.earlyIntervention = v; return this; }
        public Builder initialFitness(FitnessStatus v) { this.initialFitness = v; return this; }

        public OpenCaseCommand build() {
            return new OpenCaseCommand(employeeId, employeeName, fallbackOrgUnitCode, category, caseType, source, sourceRef,
                    incidentRef, bodyPart, occurredOn, locationCode, shift, language, templateCode, urgency, clinicalSummary,
                    intakeDetailsJson, earlyIntervention, initialFitness);
        }
    }
}
