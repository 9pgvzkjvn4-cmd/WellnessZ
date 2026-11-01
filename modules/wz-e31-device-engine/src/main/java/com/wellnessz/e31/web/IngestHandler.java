package com.wellnessz.e31.web;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceObservationGateway;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e31.adapter.hl7.Hl7Support;
import com.wellnessz.e31.adapter.xml.Poct1aAck;
import com.wellnessz.e31.adapter.xml.XPathParser;
import com.wellnessz.e31.pipeline.IngestService;
import com.wellnessz.e31.service.ObservationService;
import com.wellnessz.e31.store.MessageStore;
import com.wellnessz.e31.support.Ids;
import com.wellnessz.e31.support.TimeParsing;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Per-kind ingest handling shared by the single endpoints and {@code /device-ingest/batch} (contract section 7): builds the
 * ACQUIRE request from the common headers, maps the outcome to the protocol response (HL7 ACK AA/AE/AR, POCT1-A ACK.R01,
 * FHIR OperationOutcome, 202 receipts) and splits multi-device BLE / serial envelopes into one message per instance.
 */
@Component("e31IngestHandler")
public class IngestHandler {

    private final IngestService ingest;
    private final ObservationService observations;
    private final MessageStore messages;
    private final JsonMapper json;
    private final Clock clock;
    private final Hl7Support hl7 = new Hl7Support();

    public IngestHandler(IngestService ingest, ObservationService observations, MessageStore messages, JsonMapper json, Clock clock) {
        this.ingest = ingest;
        this.observations = observations;
        this.messages = messages;
        this.json = json;
        this.clock = clock;
    }

    /** Protocol response of one ingest call. */
    public record Response(int status, String contentType, Object body, boolean replayed) {
    }

    /** Common transport headers ({@code Idempotency-Key}, {@code X-Gateway-Sequence}, …) and file headers. */
    public record Headers(String idempotencyKey, Long sequence, Instant capturedAt, Instant edgeClock, String deviceInstance, boolean replayed,
                          Map<String, String> extra) {

        public static Headers of(Map<String, String> h) {
            return new Headers(get(h, "Idempotency-Key"), number(get(h, "X-Gateway-Sequence")), time(get(h, "X-Captured-At")),
                    time(get(h, "X-Edge-Clock")), get(h, "X-Device-Instance"), "true".equalsIgnoreCase(get(h, "X-Replayed")), h);
        }

        static String get(Map<String, String> h, String name) {
            for (Map.Entry<String, String> e : h.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) {
                    return e.getValue();
                }
            }
            return null;
        }

        static Long number(String v) {
            try {
                return v == null ? null : Long.valueOf(v.strip());
            } catch (NumberFormatException e) {
                throw ApiException.badRequest("validation_failed", "X-Gateway-Sequence must be a number");
            }
        }

        static Instant time(String v) {
            if (v == null || v.isBlank()) {
                return null;
            }
            try {
                return TimeParsing.iso(v.strip(), java.time.ZoneOffset.UTC);
            } catch (RuntimeException e) {
                throw ApiException.badRequest("validation_failed", "Time headers must be ISO-8601");
            }
        }
    }

    IngestService.Acquire acquire(IngestService.Source s, String kind, String contentType, Headers h, String instanceHint, byte[] body,
                                  Map<String, String> meta) {
        String key = h.idempotencyKey() != null ? h.idempotencyKey() : null;
        if (key == null) {
            throw ApiException.badRequest("validation_failed", "Idempotency-Key header is required");
        }
        return new IngestService.Acquire(s, kind, contentType, key, h.sequence(), h.capturedAt(), h.edgeClock(),
                instanceHint != null ? instanceHint : h.deviceInstance(), null, h.replayed(), body, meta, false, false, null, null, false);
    }

    public Map<String, Object> receipt(IngestService.Receipt r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("messageId", r.messageId());
        m.put("status", r.status());
        m.put("receivedAt", r.receivedAt());
        if (r.deviceInstanceId() != null) {
            m.put("deviceInstanceId", r.deviceInstanceId());
        }
        if (!r.observationIds().isEmpty()) {
            m.put("observationIds", r.observationIds());
        }
        return m;
    }

    // ------------------------------------------------------------------ HL7 v2 (synchronous ACK)

    public Response hl7(IngestService.Source s, Headers h, byte[] body) {
        String er7 = new String(body, StandardCharsets.ISO_8859_1);
        ca.uhn.hl7v2.model.Message parsed = null;
        try {
            parsed = hl7.parse(er7);
        } catch (Exception e) {
            // unparseable: the pipeline dead-letters it; the ACK is built by hand
        }
        Headers keyed = h.idempotencyKey() == null ? new Headers("SHA256:" + Ids.sha256Hex(body), h.sequence(), h.capturedAt(), h.edgeClock(),
                h.deviceInstance(), h.replayed(), h.extra()) : h;
        try {
            IngestService.Receipt r = ingest.acquire(acquire(s, "HL7V2", "application/hl7-v2", keyed, null, body, Map.of()));
            boolean ae = r.outcome() != null ? r.outcome().parseFailed() : parsed == null;
            String ack = hl7.ack(parsed, ae ? "AE" : "AA", ae ? errorText(r) : null, er7, clock);
            return new Response(200, "text/plain", ack, "DUPLICATE".equals(r.status()));
        } catch (ApiException e) {
            if (e.status() == HttpStatus.TOO_MANY_REQUESTS || e.status().is5xxServerError()) {
                throw e;
            }
            return new Response(200, "text/plain", hl7.ack(parsed, "AR", e.code(), er7, clock), false);
        }
    }

    static String errorText(IngestService.Receipt r) {
        return r.outcome() == null || r.outcome().errorCode() == null ? "parse_failed" : r.outcome().errorCode();
    }

    // ------------------------------------------------------------------ FHIR

    public Response fhir(IngestService.Source s, Headers h, byte[] body) {
        try {
            IngestService.Receipt r = ingest.acquire(acquire(s, "FHIR", "application/fhir+json", h, null, body, Map.of()));
            if (r.outcome() != null && "DEAD_LETTER".equals(r.outcome().status())) {
                int st = "profile_violation".equals(r.outcome().errorCode()) || "mapping_missing".equals(r.outcome().errorCode()) ? 422 : 400;
                return new Response(st, "application/fhir+json", outcome("error", "invalid", r.outcome().errorCode(), r.messageId()), false);
            }
            return new Response(202, MediaType.APPLICATION_JSON_VALUE, receipt(r), "DUPLICATE".equals(r.status()));
        } catch (ApiException e) {
            return new Response(e.status().value(), "application/fhir+json", outcome("error", e.status() == HttpStatus.UNPROCESSABLE_CONTENT
                    ? "business-rule" : "processing", e.code(), null), false);
        }
    }

    Map<String, Object> outcome(String severity, String code, String detail, UUID messageId) {
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("severity", severity);
        issue.put("code", code);
        issue.put("details", Map.of("coding", List.of(Map.of("system", "urn:wellnessz:problem", "code", detail == null ? "error" : detail))));
        if (messageId != null) {
            issue.put("diagnostics", "messageId " + messageId);
        }
        return Map.of("resourceType", "OperationOutcome", "issue", List.of(issue));
    }

    // ------------------------------------------------------------------ POCT1-A

    public Response poct1a(IngestService.Source s, Headers h, byte[] body) {
        String control = XPathParser.controlId(body);
        Headers keyed = h.idempotencyKey() == null && control != null
                ? new Headers("POCT:" + control, h.sequence(), h.capturedAt(), h.edgeClock(), h.deviceInstance(), h.replayed(), h.extra()) : h;
        try {
            IngestService.Receipt r = ingest.acquire(acquire(s, "POCT1A", "application/xml", keyed, null, body, Map.of()));
            boolean ae = r.outcome() != null && "DEAD_LETTER".equals(r.outcome().status());
            return new Response(200, MediaType.APPLICATION_XML_VALUE, Poct1aAck.of(ae ? "AE" : "AA", control, ae ? errorText(r) : null, clock.instant()),
                    "DUPLICATE".equals(r.status()));
        } catch (ApiException e) {
            if (e.status() == HttpStatus.TOO_MANY_REQUESTS || e.status().is5xxServerError()) {
                throw e;
            }
            return new Response(200, MediaType.APPLICATION_XML_VALUE, Poct1aAck.of("AE", control, e.code(), clock.instant()), false);
        }
    }

    // ------------------------------------------------------------------ files (P2 exports) with PDF pairing

    public Response file(IngestService.Source s, Headers h, byte[] body, String contentType) {
        String name = Headers.get(h.extra(), "X-File-Name");
        String format = Headers.get(h.extra(), "X-File-Format");
        String sha = Headers.get(h.extra(), "X-File-Sha256");
        if (format == null || !List.of("CSV", "XML", "GDT", "PDF").contains(format.toUpperCase(Locale.ROOT))) {
            throw ApiException.badRequest("validation_failed", "X-File-Format must be CSV, XML, GDT or PDF");
        }
        if (sha != null && !Ids.constantTimeEquals(sha, Ids.sha256Hex(body))) {
            throw ApiException.badRequest("file_hash_mismatch", "X-File-Sha256 does not match the body");
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("fileFormat", format.toUpperCase(Locale.ROOT));
        String stem = stem(name);
        if (stem != null) {
            meta.put("fileStemHash", Ids.sha256Hex(s.endpointId() + "|" + stem));
        }
        String sourcePath = Headers.get(h.extra(), "X-Source-Path-Hash");
        if (sourcePath != null && sourcePath.matches("[0-9a-fA-F]{16,64}")) {
            meta.put("sourcePathHash", sourcePath);
        }
        Headers keyed = h.idempotencyKey() == null ? new Headers("FILE:" + Ids.sha256Hex(body), h.sequence(), h.capturedAt(), h.edgeClock(),
                h.deviceInstance(), h.replayed(), h.extra()) : h;
        if ("PDF".equals(meta.get("fileFormat"))) {
            IngestService.Receipt r = ingest.acquireAttachmentFile(acquire(s, "FILE", contentType == null ? "application/pdf" : contentType, keyed, null,
                    body, meta));
            return new Response(202, MediaType.APPLICATION_JSON_VALUE, receipt(r), "DUPLICATE".equals(r.status()));
        }
        IngestService.Receipt r = ingest.acquire(acquire(s, "FILE", contentType, keyed, null, body, meta));
        return new Response(202, MediaType.APPLICATION_JSON_VALUE, receipt(r), "DUPLICATE".equals(r.status()));
    }

    static String stem(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String n = name.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1);
        int dot = n.lastIndexOf('.');
        return (dot > 0 ? n.substring(0, dot) : n).toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ BLE / serial envelopes

    /** Splits {@code {records:[…]}} by record-level device instance; one message (and receipt entry) per instance group. */
    public Response records(IngestService.Source s, Headers h, byte[] body, String kind) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JacksonException e) {
            throw ApiException.badRequest("validation_failed", "The body must be a JSON object with records");
        }
        JsonNode records = root == null ? null : root.get("records");
        if (records == null || !records.isArray() || records.isEmpty() || records.size() > 100) {
            throw ApiException.badRequest("validation_failed", "1–100 records are required");
        }
        Map<String, ArrayNode> groups = new LinkedHashMap<>();
        for (JsonNode r : records) {
            String id = r.hasNonNull("deviceInstanceId") ? r.get("deviceInstanceId").asString() : h.deviceInstance();
            groups.computeIfAbsent(id == null ? "" : id, k -> json.createArrayNode()).add(r);
        }
        List<UUID> observationIds = new ArrayList<>();
        IngestService.Receipt first = null;
        for (Map.Entry<String, ArrayNode> g : groups.entrySet()) {
            ObjectNode envelope = json.createObjectNode();
            envelope.set("records", g.getValue());
            String key = groups.size() == 1 ? h.idempotencyKey() : h.idempotencyKey() + "#" + g.getKey();
            Headers gh = new Headers(key, h.sequence(), h.capturedAt(), h.edgeClock(), h.deviceInstance(), h.replayed(), h.extra());
            IngestService.Receipt r = ingest.acquire(acquire(s, kind, "application/json", gh, g.getKey().isEmpty() ? null : g.getKey(),
                    json.writeValueAsBytes(envelope), Map.of()));
            observationIds.addAll(r.observationIds());
            if (first == null) {
                first = r;
            }
        }
        Map<String, Object> receipt = receipt(first);
        if (!observationIds.isEmpty()) {
            receipt.put("observationIds", observationIds);
        }
        return new Response(202, MediaType.APPLICATION_JSON_VALUE, receipt, "DUPLICATE".equals(first.status()));
    }

    // ------------------------------------------------------------------ DICOMweb metadata

    public Response dicom(IngestService.Source s, Headers h, byte[] body) {
        Map<String, String> meta = new LinkedHashMap<>();
        String archive = com.wellnessz.e31.adapter.dicom.DicomJsonParser.archiveId(json, body);
        if (archive == null) {
            archive = Headers.get(h.extra(), "X-Instance-Archive-Id");
        }
        if (archive != null) {
            try {
                meta.put("attachmentArchiveId", UUID.fromString(archive).toString());
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("validation_failed", "archiveId must be a UUID");
            }
        }
        IngestService.Receipt r = ingest.acquire(acquire(s, "DICOMWEB", "application/dicom+json", h, null, body, meta));
        return new Response(202, MediaType.APPLICATION_JSON_VALUE, receipt(r), "DUPLICATE".equals(r.status()));
    }

    // ------------------------------------------------------------------ fall-back replay from the tablet

    public Response fallback(IngestService.Source s, Headers h, byte[] body) {
        JsonNode f;
        try {
            f = json.readTree(body);
        } catch (JacksonException e) {
            throw ApiException.badRequest("validation_failed", "The body must be JSON");
        }
        if (f == null || !f.isObject() || !f.has("values") || !f.has("category") || !f.has("method")) {
            throw ApiException.badRequest("validation_failed", "method, category and values are required");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : f.get("values").properties()) {
            JsonNode v = e.getValue();
            values.put(e.getKey(), v.isNumber() ? v.decimalValue() : v.isBoolean() ? Boolean.valueOf(v.asBoolean()) : v.asString());
        }
        DeviceObservationGateway.FallbackSubmission sub;
        try {
            sub = ObservationService.submission(f.hasNonNull("sessionId") ? UUID.fromString(f.get("sessionId").asString()) : null,
                    DeviceCodes.CaptureMethod.valueOf(f.get("method").asString()), text(f, "deviceInstanceId"), text(f, "attestedModel"),
                    text(f, "attestedSerial"), text(f, "attestedCalibrationId"), TimeParsing.date(text(f, "attestedCalibrationDate")),
                    DeviceCodes.Category.valueOf(f.get("category").asString()), text(f, "employeeId"), text(f, "formCode"), values,
                    f.hasNonNull("imageArchiveId") ? UUID.fromString(f.get("imageArchiveId").asString()) : null,
                    f.hasNonNull("ocrConfidence") ? f.get("ocrConfidence").decimalValue() : null, text(f, "attestation"));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("validation_failed", "Invalid fall-back submission");
        }
        String operator = text(f, "operator");
        if (operator == null) {
            throw ApiException.badRequest("validation_failed", "operator is required");
        }
        Instant captured = f.hasNonNull("capturedAt") ? Headers.time(f.get("capturedAt").asString()) : h.capturedAt();
        if (h.idempotencyKey() == null) {
            throw ApiException.badRequest("validation_failed", "Idempotency-Key header is required");
        }
        IngestService.Receipt r = observations.submitFallback(operator, sub, captured, operator, h.idempotencyKey(), s);
        return new Response(202, MediaType.APPLICATION_JSON_VALUE, receipt(r), "DUPLICATE".equals(r.status()));
    }

    static String text(JsonNode n, String member) {
        return n.hasNonNull(member) ? n.get(member).asString() : null;
    }

    public MessageStore messages() {
        return messages;
    }
}
