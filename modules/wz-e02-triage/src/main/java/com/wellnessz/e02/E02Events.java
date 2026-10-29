package com.wellnessz.e02;

import com.wellnessz.common.events.DomainEvent;

/** Events published by E02 (consumed by the E01 notification matrix and analytics). Routing metadata only. */
public final class E02Events {

    private E02Events() {
    }

    public record CaseAssigned(String caseId, String caseRef, String queueCode, String ownerUsername) implements DomainEvent {
        public String eventType() {
            return "case.assigned.v1";
        }

        public String aggregateType() {
            return "CASE";
        }

        public String aggregateId() {
            return caseId;
        }
    }

    public record CaseTriaged(String caseId, String caseRef, String urgency, String caseType) implements DomainEvent {
        public String eventType() {
            return "case.triaged.v1";
        }

        public String aggregateType() {
            return "CASE";
        }

        public String aggregateId() {
            return caseId;
        }
    }

    public record SlaAlert(String caseId, String caseRef, String milestone, String level, String type) implements DomainEvent {
        public String eventType() {
            return type;
        }

        public String aggregateType() {
            return "CASE";
        }

        public String aggregateId() {
            return caseId;
        }
    }

    public record CaseEscalated(String caseId, String caseRef, String level, String reasonCode) implements DomainEvent {
        public String eventType() {
            return "case.escalated.v1";
        }

        public String aggregateType() {
            return "CASE";
        }

        public String aggregateId() {
            return caseId;
        }
    }
}
