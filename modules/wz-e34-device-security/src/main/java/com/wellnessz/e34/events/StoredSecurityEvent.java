package com.wellnessz.e34.events;

import java.time.Instant;
import java.util.Map;

/** A security event as stored in {@code devicesec.security_event} (PHI-free) – also the API view. */
public record StoredSecurityEvent(long id, String eventId, String type, String severity, String endpointId, String deviceInstanceId,
                                  String siteCode, String zone, String actor, String sourceModule, String reasonCode, String remoteAddress,
                                  Map<String, String> attributes, Instant occurredAt, String hash) {

    public StoredSecurityEvent {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public String attribute(String key) {
        return attributes.get(key);
    }
}
