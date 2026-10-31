package com.wellnessz.e34.events;

import java.time.Instant;

import com.wellnessz.common.events.DomainEvent;

/** Outbox events of E34: SIEM forwarding, ITSM tickets and the in-process signing-key revocation notice. */
public final class E34Events {

    private E34Events() {
    }

    public static final String SIEM_DESTINATION = "siem-device-security";
    public static final String ITSM_DESTINATION = "itsm-tickets";

    /**
     * {@code device.security.event.v1} to the SIEM – flat, exactly the allow-listed fields of {@code siem-device-security}
     * (application.yml), no PHI.
     */
    public record SiemSecurityEvent(String messageId, String eventId, String type, String severity, String endpointId, String deviceInstanceId,
                                    String siteCode, String zone, String sourceModule, String reasonCode, String actor, String remoteAddress,
                                    String protocol, String port, String rate, String keyId, String chainHash, String eventType,
                                    Instant occurredAt) implements DomainEvent {

        public static final String TYPE = "device.security.event.v1";

        @Override
        public String aggregateType() {
            return "DEVICE_SECURITY_EVENT";
        }

        @Override
        public String aggregateId() {
            return eventId;
        }
    }

    /** {@code device.security.ticket.v1} to IT service management for HIGH / CRITICAL alerts (allow-list {@code itsm-tickets}). */
    public record ItsmTicket(String messageId, String ticketRef, String deviceInstanceId, String siteCode, String alertType, String severity,
                             Instant raisedAt) implements DomainEvent {

        @Override
        public String eventType() {
            return "device.security.ticket.v1";
        }

        @Override
        public String aggregateType() {
            return "DEVICE_SECURITY_ALERT";
        }

        @Override
        public String aggregateId() {
            return ticketRef;
        }

        @Override
        public Instant occurredAt() {
            return raisedAt;
        }
    }

    /**
     * {@code device.signing-key.revoked.v1} (in-process): packages signed with the key become REVOKED in the E32 registry and
     * their instances SUSPENDED (contract §15.5). Consumers subscribe by event type – no class dependency.
     */
    public record SigningKeyRevoked(String keyId, String revokedBy, Instant revokedAt) implements DomainEvent {

        @Override
        public String eventType() {
            return "device.signing-key.revoked.v1";
        }

        @Override
        public String aggregateType() {
            return "DEVICE_SIGNING_KEY";
        }

        @Override
        public String aggregateId() {
            return keyId;
        }

        @Override
        public Instant occurredAt() {
            return revokedAt;
        }
    }
}
