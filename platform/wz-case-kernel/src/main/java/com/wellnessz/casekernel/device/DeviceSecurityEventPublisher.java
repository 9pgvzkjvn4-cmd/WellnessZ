package com.wellnessz.casekernel.device;

import java.time.Instant;
import java.util.Map;

/**
 * Security event sink (E34-HLR-08 / -09), implemented by E34: stores the event in its tamper-evident store, evaluates the
 * anomaly rules and forwards to the SIEM outbox. Called by the ingest filter, the engine (E31), the console (E32) and
 * capture (E33). Events are PHI-free: no employee id, no values, no payload. Contract section 15.9.
 */
public interface DeviceSecurityEventPublisher {

    void publish(DeviceSecurityEvent event);

    /**
     * @param type         event type from the catalogue, e.g. {@code ENDPOINT_AUTH_FAILED}, {@code UNKNOWN_DEVICE_IDENTITY},
     *                     {@code MESSAGE_RATE_SPIKE}, {@code CONFIG_CHANGE_APPROVED}, {@code PACKAGE_SIGNATURE_INVALID}
     * @param sourceModule E31 | E32 | E33 | E34 | KERNEL
     * @param reasonCode   machine reason (e.g. {@code bad_token})
     * @param attributes   PHI-free attributes (remote address, protocol, port, rate, key id …)
     */
    record DeviceSecurityEvent(String type, DeviceCodes.Severity severity, String endpointId, String deviceInstanceId, String siteCode,
                               String actor, String sourceModule, String reasonCode, Map<String, String> attributes, Instant occurredAt) {

        public DeviceSecurityEvent {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }
}
