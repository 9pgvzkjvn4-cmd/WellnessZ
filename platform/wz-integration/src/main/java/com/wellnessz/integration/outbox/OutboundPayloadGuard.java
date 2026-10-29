package com.wellnessz.integration.outbox;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import com.wellnessz.common.error.ApiException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Enforces the <b>status-only disclosure rule</b> at the integration boundary: for every external destination an
 * explicit allow-list of JSON fields is configured; any other field (e.g. diagnosis, clinical notes) blocks the
 * publication. Destinations without an allow-list are refused (default deny).
 *
 * <pre>
 * wellnessz.integration.outbound-allowlist.crew-rostering=caseRef,employeeId,fitnessStatus,restrictionUntil,effectiveFrom
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "wellnessz.integration")
public class OutboundPayloadGuard {

    private Map<String, List<String>> outboundAllowlist = new HashMap<>();
    private final JsonMapper json;

    public OutboundPayloadGuard(JsonMapper json) {
        this.json = json;
    }

    public void check(String destination, String payload) {
        if (InProcessEventPublisher.DESTINATION.equals(destination)) {
            return;
        }
        List<String> allowed = outboundAllowlist.get(destination);
        if (allowed == null) {
            throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "destination_not_allowed",
                    "No outbound allow-list configured for destination");
        }
        Set<String> allowedSet = Set.copyOf(allowed);
        JsonNode node = json.readTree(payload);
        Iterator<String> names = node.propertyNames().iterator();
        while (names.hasNext()) {
            String field = names.next();
            JsonNode value = node.get(field);
            if (value != null && (value.isObject() || value.isArray())) {
                throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "disclosure_violation",
                        "Outbound payloads to enterprise destinations must be flat (no nested objects or arrays)");
            }
            if (!allowedSet.contains(field) && !Set.of("eventType", "aggregateType", "aggregateId", "occurredAt").contains(field)) {
                throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "disclosure_violation",
                        "Outbound payload contains a field that is not permitted for this destination");
            }
        }
    }

    public Map<String, List<String>> getOutboundAllowlist() {
        return outboundAllowlist;
    }

    public void setOutboundAllowlist(Map<String, List<String>> outboundAllowlist) {
        this.outboundAllowlist = outboundAllowlist;
    }
}
