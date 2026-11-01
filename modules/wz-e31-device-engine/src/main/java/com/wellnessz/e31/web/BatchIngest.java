package com.wellnessz.e31.web;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.e31.pipeline.IngestService;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Item-by-item processing of a batch envelope ({@code /device-ingest/batch} after store-and-forward and
 * {@code /device-ingest/retrievals/{id}/batch} for historic results – contract section 7): items in {@code sequence} order, each
 * through the per-kind handler with its own idempotency key, one result per item.
 */
@Component("e31BatchIngest")
public class BatchIngest {

    static final long MAX_BYTES = 10L * 1024 * 1024;

    private final IngestHandler handler;
    private final JsonMapper json;

    public BatchIngest(IngestHandler handler, JsonMapper json) {
        this.handler = handler;
        this.json = json;
    }

    /** Parses and checks the envelope: JSON, 1–100 items, at most 10 MB. */
    public JsonNode items(byte[] body) {
        if (body.length > MAX_BYTES) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "payload_too_large", "A batch is limited to 10 MB");
        }
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JacksonException e) {
            throw ApiException.badRequest("validation_failed", "The batch must be JSON");
        }
        JsonNode items = root == null ? null : root.get("items");
        if (items == null || !items.isArray() || items.isEmpty() || items.size() > 100) {
            throw ApiException.badRequest("validation_failed", "1–100 items are required");
        }
        return items;
    }

    public List<Map<String, Object>> process(IngestService.Source s, JsonNode items) {
        List<JsonNode> sorted = new ArrayList<>();
        items.forEach(sorted::add);
        sorted.sort(Comparator.comparingLong(n -> n.path("sequence").asLong(Long.MAX_VALUE)));
        List<Map<String, Object>> results = new ArrayList<>();
        for (JsonNode item : sorted) {
            results.add(one(s, item));
        }
        return results;
    }

    Map<String, Object> one(IngestService.Source s, JsonNode item) {
        String key = item.path("idempotencyKey").asString("");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("idempotencyKey", key);
        try {
            Map<String, String> h = new LinkedHashMap<>();
            item.path("headers").properties().forEach(e -> h.put(e.getKey(), e.getValue().asString()));
            h.put("Idempotency-Key", key);
            if (item.hasNonNull("sequence")) {
                h.put("X-Gateway-Sequence", item.get("sequence").asString());
            }
            if (item.hasNonNull("capturedAt")) {
                h.put("X-Captured-At", item.get("capturedAt").asString());
            }
            h.putIfAbsent("X-Replayed", "true");
            IngestHandler.Headers headers = IngestHandler.Headers.of(h);
            byte[] payload = payload(item);
            String contentType = item.path("contentType").asString(null);
            IngestHandler.Response r = switch (item.path("kind").asString("")) {
                case "HL7V2" -> handler.hl7(s, headers, payload);
                case "FHIR" -> handler.fhir(s, headers, payload);
                case "POCT1A" -> handler.poct1a(s, headers, payload);
                case "FILE" -> handler.file(s, headers, payload, contentType);
                case "BLE" -> handler.records(s, headers, payload, "BLE");
                case "SERIAL" -> handler.records(s, headers, payload, "SERIAL");
                case "FALLBACK" -> handler.fallback(s, headers, payload);
                default -> throw ApiException.badRequest("validation_failed", "Unknown item kind");
            };
            result.put("httpStatus", r.status());
            if (r.replayed()) {
                result.put("replayed", true);
            }
            if (r.body() instanceof String ack) {
                result.put("ack", ack);
            } else {
                result.put("receipt", r.body());
            }
        } catch (ApiException e) {
            result.put("httpStatus", e.status().value());
            result.put("problem", Map.of("status", e.status().value(), "code", e.code(), "detail", String.valueOf(e.getMessage())));
        }
        return result;
    }

    byte[] payload(JsonNode item) {
        JsonNode b = item.get("body");
        if (b == null || b.isNull()) {
            throw ApiException.badRequest("validation_failed", "Item body missing");
        }
        if (b.isObject() || b.isArray()) {
            return json.writeValueAsBytes(b);
        }
        String text = b.asString();
        boolean base64 = "BASE64".equalsIgnoreCase(item.path("bodyEncoding").asString(""))
                || item.path("contentType").asString("").startsWith("application/pdf");
        if (base64) {
            try {
                return Base64.getDecoder().decode(text);
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("validation_failed", "Item body is not valid base64");
            }
        }
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
