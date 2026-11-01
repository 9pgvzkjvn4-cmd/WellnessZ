package com.wellnessz.e31;

import com.wellnessz.common.events.DomainEvent;

/** In-process E31 events (contract section 17) – identifiers, category and status only, never values or employee data. */
public final class E31Events {

    private E31Events() {
    }

    /** {@code device.observation.routed.v1}: an observation was filed into its target module. */
    public record ObservationRouted(String observationId, String category, String target, String routedRecordId, String deviceInstanceId)
            implements DomainEvent {
        public static final String TYPE = "device.observation.routed.v1";

        @Override
        public String eventType() {
            return TYPE;
        }

        @Override
        public String aggregateType() {
            return "DEVICE_OBSERVATION";
        }

        @Override
        public String aggregateId() {
            return observationId;
        }
    }

    /**
     * {@code device.qc.received.v1}: a QC message (POCT1-A QCN.R01) arrived for an instance – E32 records it as a QC check.
     * Payload per contract section 13.6: {@code {deviceInstanceId, checkType, lot, lotExpiry, outcome, performedAt}} (PHI-free);
     * {@code messageId} / {@code messageType} are kept for tracing.
     */
    public record QcReceived(String messageId, String deviceInstanceId, String messageType, String checkType, String lot, String lotExpiry,
                             String outcome, String performedAt) implements DomainEvent {
        public static final String TYPE = "device.qc.received.v1";

        @Override
        public String eventType() {
            return TYPE;
        }

        @Override
        public String aggregateType() {
            return "DEVICE_INSTANCE";
        }

        @Override
        public String aggregateId() {
            return deviceInstanceId == null ? messageId : deviceInstanceId;
        }
    }
}
