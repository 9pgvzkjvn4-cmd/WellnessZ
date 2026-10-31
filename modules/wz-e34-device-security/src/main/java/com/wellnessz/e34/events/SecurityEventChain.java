package com.wellnessz.e34.events;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.wellnessz.common.web.LogSanitizer;
import com.wellnessz.e34.support.TokenHasher;

/**
 * Pure functions of the tamper-evident security event store (contract §15.9): attribute minimisation (allow-listed,
 * PHI-free keys only), the canonical form of an event and the hash chain {@code hash = SHA-256(previous | canonical)}.
 */
public final class SecurityEventChain {

    public static final String GENESIS = "0".repeat(64);

    /** Attribute keys an event may carry – everything else is dropped (no payloads, values or person identifiers). */
    public static final Set<String> ALLOWED_ATTRIBUTES = Set.of("remoteAddress", "method", "path", "protocol", "port", "rate", "limit",
            "keyId", "algorithm", "connectorId", "baseline", "status", "findings", "skewMillis", "schema", "count", "certificateSerial",
            "purpose", "reference", "scope", "script", "outcome", "environment", "version", "configurationId", "ruleCode", "alertId",
            "incidentRef", "minutes", "kind", "previousThumbprint", "thumbprint", "issuer", "format", "deviceSerialHash", "listener");

    private static final Pattern TYPE = Pattern.compile("[A-Z][A-Z0-9_]{2,59}");
    private static final Pattern MODULE = Pattern.compile("E31|E32|E33|E34|KERNEL");

    private SecurityEventChain() {
    }

    public static String safeType(String type) {
        return type != null && TYPE.matcher(type).matches() ? type : "INVALID_EVENT_TYPE";
    }

    public static String safeModule(String module) {
        return module != null && MODULE.matcher(module).matches() ? module : "KERNEL";
    }

    /** Allow-listed attributes, values sanitised (no control characters) and capped at 200 characters. */
    public static Map<String, String> minimise(Map<String, String> attributes) {
        TreeMap<String, String> out = new TreeMap<>();
        if (attributes == null) {
            return out;
        }
        attributes.forEach((k, v) -> {
            if (k != null && v != null && ALLOWED_ATTRIBUTES.contains(k)) {
                String clean = LogSanitizer.clean(v);
                out.put(k, clean.length() > 200 ? clean.substring(0, 200) : clean);
            }
        });
        return out;
    }

    /** Canonical, order-stable rendering of the stored fields (attributes already canonical JSON). */
    public static String canonical(String eventId, String type, String severity, String endpointId, String deviceInstanceId, String siteCode,
                                   String zone, String actor, String sourceModule, String reasonCode, String remoteAddress,
                                   String attributesJson, Instant occurredAt) {
        return String.join("|", nz(eventId), nz(type), nz(severity), nz(endpointId), nz(deviceInstanceId), nz(siteCode), nz(zone), nz(actor),
                nz(sourceModule), nz(reasonCode), nz(remoteAddress), nz(attributesJson), occurredAt == null ? "" : occurredAt.toString());
    }

    public static String link(String previousHash, String canonical) {
        return TokenHasher.sha256Hex(previousHash + "|" + canonical);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
