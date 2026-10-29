package com.wellnessz.e03;

import com.wellnessz.common.events.DomainEvent;

/** In-process E03 events (contract §6). Payloads carry identifiers and codes of state, never clinical content. */
public final class E03Events {

    private E03Events() {
    }

    /** An encounter was signed (locked). */
    public record EncounterSigned(String encounterId, String employeeId, String caseId, String type) implements DomainEvent {
        public static final String TYPE = "encounter.signed.v1";

        @Override
        public String eventType() {
            return TYPE;
        }

        @Override
        public String aggregateType() {
            return "ENCOUNTER";
        }

        @Override
        public String aggregateId() {
            return encounterId;
        }
    }

    /** A result was received (LIS, device or manual entry) with its abnormal flag. */
    public record ResultReceived(String resultId, String employeeId, String flag) implements DomainEvent {
        public static final String TYPE = "result.received.v1";

        @Override
        public String eventType() {
            return TYPE;
        }

        @Override
        public String aggregateType() {
            return "RESULT";
        }

        @Override
        public String aggregateId() {
            return resultId;
        }
    }
}
