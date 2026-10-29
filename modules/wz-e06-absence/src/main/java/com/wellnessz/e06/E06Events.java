package com.wellnessz.e06;

import java.time.LocalDate;

import com.wellnessz.common.events.DomainEvent;

/** Events emitted by E06 (contract §6) – identifiers and status only, never absence reasons or certificate content. */
public final class E06Events {

    public static final String HR_DESTINATION = "hr-systems";

    private E06Events() {
    }

    /** In-process: a trigger rule raised a queue item. */
    public record TriggerRaised(String queueItemId, String employeeId, String action) implements DomainEvent {
        @Override
        public String eventType() {
            return "absence.trigger.raised.v1";
        }

        @Override
        public String aggregateType() {
            return "ABSENCE_QUEUE_ITEM";
        }

        @Override
        public String aggregateId() {
            return queueItemId;
        }
    }

    /** In-process: a long-term absence pathway started. */
    public record LtaStarted(String caseId, String employeeId) implements DomainEvent {
        @Override
        public String eventType() {
            return "absence.lta.started.v1";
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

    /** In-process: an OH advice report was released to the requester with the employee's consent. */
    public record AdviceReleased(String requestId, String employeeId, String documentId) implements DomainEvent {
        @Override
        public String eventType() {
            return "absence.advice.released.v1";
        }

        @Override
        public String aggregateType() {
            return "ADVICE_REQUEST";
        }

        @Override
        public String aggregateId() {
            return requestId;
        }
    }

    /**
     * HRIS status note → {@code hr-systems} (allow-listed): OH engagement and certificate validity only. Exactly one of
     * {@code ohEngaged} / {@code certificateValidity} is set per message.
     */
    public record HrAbsenceStatus(String messageId, String employeeId, Boolean ohEngaged, String certificateValidity, LocalDate effectiveFrom)
            implements DomainEvent {
        @Override
        public String eventType() {
            return "absence.hr-status.v1";
        }

        @Override
        public String aggregateType() {
            return "EMPLOYEE";
        }

        @Override
        public String aggregateId() {
            return employeeId;
        }
    }
}
