package com.wellnessz.casekernel.model;

import java.time.LocalDate;

import com.wellnessz.common.events.DomainEvent;

/** Events emitted by the case kernel. */
public final class CaseEvents {

    private CaseEvents() {
    }

    public record CaseOpened(String caseId, String caseRef, String category, String source) implements DomainEvent {
        @Override
        public String eventType() {
            return "case.opened.v1";
        }

        @Override
        public String aggregateType() {
            return "CASE";
        }

        @Override
        public String aggregateId() {
            return caseId;
        }
    }

    /** Status-only feed to rostering / HR – the payload is limited by the outbound allow-list. */
    public record FitnessStatusChanged(String caseRef, String employeeId, String fitnessStatus, LocalDate restrictionUntil,
                                       LocalDate effectiveFrom) implements DomainEvent {
        @Override
        public String eventType() {
            return "fitness.status.changed.v1";
        }

        @Override
        public String aggregateType() {
            return "CASE";
        }

        @Override
        public String aggregateId() {
            return caseRef;
        }
    }
}
