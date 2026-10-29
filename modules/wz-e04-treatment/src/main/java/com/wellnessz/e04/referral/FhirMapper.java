package com.wellnessz.e04.referral;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** FHIR R4 mapping of e-referrals: outbound {@code ServiceRequest}, inbound {@code DocumentReference} (provider report). */
public final class FhirMapper {

    public static final String REFERRAL_SYSTEM = "urn:wellnessz:referral";
    public static final String SERVICE_SYSTEM = "urn:wellnessz:referral-service";
    public static final String EMPLOYEE_SYSTEM = "urn:wellnessz:employee";
    public static final String ORGANISATION_SYSTEM = "urn:wellnessz:partner-organisation";

    private FhirMapper() {
    }

    /** Everything the ServiceRequest needs, already resolved by the caller. */
    public record Source(UUID id, String ref, String status, String urgency, String serviceCode, String serviceName, String providerCode,
                         String providerName, String employeeId, String employeeName, String requester, String reason, String question,
                         Map<String, Object> dataSet, Instant authoredOn, Instant appointmentAt, List<UUID> documentIds) {
    }

    public static String fhirStatus(String status) {
        return switch (status) {
            case "DRAFT" -> "draft";
            case "CANCELLED", "DECLINED" -> "revoked";
            case "REPORT_RECEIVED", "CLOSED" -> "completed";
            default -> "active";
        };
    }

    public static Map<String, Object> serviceRequest(Source s) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("resourceType", "ServiceRequest");
        r.put("id", s.id().toString());
        r.put("identifier", List.of(Map.of("system", REFERRAL_SYSTEM, "value", s.ref())));
        r.put("status", fhirStatus(s.status()));
        r.put("intent", "order");
        r.put("category", List.of(Map.of("coding", List.of(Map.of("system", "http://snomed.info/sct", "code", "3457005",
                "display", "Patient referral")))));
        r.put("priority", "URGENT".equals(s.urgency()) ? "urgent" : "routine");
        r.put("code", Map.of("coding", List.of(Map.of("system", SERVICE_SYSTEM, "code", s.serviceCode(), "display", s.serviceName())),
                "text", s.serviceName()));
        r.put("subject", Map.of("identifier", Map.of("system", EMPLOYEE_SYSTEM, "value", s.employeeId()), "display", s.employeeName()));
        if (s.authoredOn() != null) {
            r.put("authoredOn", s.authoredOn().toString());
        }
        if (s.appointmentAt() != null) {
            r.put("occurrenceDateTime", s.appointmentAt().toString());
        }
        r.put("requester", Map.of("display", s.requester() == null ? "WellnessZ Occupational Health" : s.requester()));
        if (s.providerCode() != null) {
            r.put("performer", List.of(Map.of("identifier", Map.of("system", ORGANISATION_SYSTEM, "value", s.providerCode()),
                    "display", s.providerName() == null ? s.providerCode() : s.providerName())));
        }
        if (s.reason() != null) {
            r.put("reasonCode", List.of(Map.of("text", s.reason())));
        }
        if (s.question() != null) {
            r.put("note", List.of(Map.of("text", s.question())));
        }
        List<Map<String, Object>> info = new ArrayList<>();
        if (s.dataSet() != null) {
            s.dataSet().forEach((k, v) -> info.add(Map.of("display", k + ": " + (v instanceof List<?> l ? String.join(", ",
                    l.stream().map(String::valueOf).toList()) : String.valueOf(v)))));
        }
        if (s.documentIds() != null) {
            s.documentIds().forEach(d -> info.add(Map.of("reference", "DocumentReference/" + d)));
        }
        if (!info.isEmpty()) {
            r.put("supportingInfo", info);
        }
        return r;
    }

    /** Inbound provider report: the ServiceRequest id (or referral reference) the DocumentReference relates to. */
    @SuppressWarnings("unchecked")
    public static Optional<String> relatedServiceRequest(Map<String, Object> documentReference) {
        if (documentReference == null || !"DocumentReference".equals(documentReference.get("resourceType"))) {
            return Optional.empty();
        }
        if (documentReference.get("context") instanceof Map<?, ?> ctx && ctx.get("related") instanceof List<?> related) {
            for (Object o : related) {
                if (o instanceof Map<?, ?> m) {
                    if (m.get("reference") instanceof String ref && ref.startsWith("ServiceRequest/")) {
                        return Optional.of(ref.substring("ServiceRequest/".length()));
                    }
                    if (m.get("identifier") instanceof Map<?, ?> id && id.get("value") instanceof String v) {
                        return Optional.of(v);
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** Base64 attachment data, title and description (summary) of the first content entry. */
    public record Attachment(String data, String title, String description) {
    }

    public static Optional<Attachment> attachment(Map<String, Object> documentReference) {
        if (documentReference.get("content") instanceof List<?> content && !content.isEmpty()
                && content.get(0) instanceof Map<?, ?> c0 && c0.get("attachment") instanceof Map<?, ?> a && a.get("data") instanceof String data) {
            return Optional.of(new Attachment(data, a.get("title") instanceof String t ? t : null,
                    documentReference.get("description") instanceof String d ? d : null));
        }
        return Optional.empty();
    }
}
