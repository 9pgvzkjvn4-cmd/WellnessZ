package com.wellnessz.e05;

import java.time.Instant;
import java.time.LocalDate;

import com.wellnessz.common.events.DomainEvent;

/** Events emitted by E05. Enterprise destinations receive flat, allow-listed, status-only payloads. */
public final class E05Events {

    public static final String HR_DESTINATION = "hr-systems";
    public static final String HYGIENE_DESTINATION = "catering-hygiene";

    private E05Events() {
    }

    /** In-process only (contract §6). */
    public record FitnessDecisionMade(String decisionId, String caseId, String employeeId, String outcome, String standardCode)
            implements DomainEvent {
        @Override
        public String eventType() {
            return "fitness.decision.made.v1";
        }

        @Override
        public String aggregateType() {
            return "FITNESS_DECISION";
        }

        @Override
        public String aggregateId() {
            return decisionId;
        }
    }

    /** In-process only: a restriction ends within the alert window (E05-BL-016). */
    public record RestrictionExpiring(String restrictionId, String employeeId, LocalDate validUntil) implements DomainEvent {
        @Override
        public String eventType() {
            return "fitness.restriction.expiring.v1";
        }

        @Override
        public String aggregateType() {
            return "RESTRICTION";
        }

        @Override
        public String aggregateId() {
            return restrictionId;
        }
    }

    /** HRIS note (E05-BL-008) → {@code hr-systems}: whether the employee accepted the alternative-duty offer, nothing else. */
    public record JobOfferResponded(String messageId, String employeeId, String offerId, boolean accepted, Instant respondedAt)
            implements DomainEvent {
        @Override
        public String eventType() {
            return "fitness.job-offer.responded.v1";
        }

        @Override
        public String aggregateType() {
            return "JOB_OFFER";
        }

        @Override
        public String aggregateId() {
            return offerId;
        }
    }

    /** Catering hygiene feed (E05-BL-014) → {@code catering-hygiene}: status and dates only. */
    public record FoodHandlerStatusChanged(String employeeId, String foodHandlerStatus, LocalDate excludedFrom, LocalDate expectedReturn)
            implements DomainEvent {
        @Override
        public String eventType() {
            return "fitness.food-handler.status.changed.v1";
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
