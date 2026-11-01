package com.wellnessz.e31.web;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.casekernel.device.DeviceEndpointPrincipal;
import com.wellnessz.casekernel.module.ModuleRegistry;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e31.pipeline.IngestService;
import com.wellnessz.e31.service.GatewayService;
import com.wellnessz.e31.store.RawPayloadStore;
import com.wellnessz.e31.support.EngineSettings;
import com.wellnessz.e31.support.Ids;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Machine ingest API for gateways, Device Host agents, the nurse tablet and kiosks (contract section 7). Authentication is
 * done by the kernel device-ingest filter (mTLS thumbprint + per-endpoint token, E34 decides – fail closed); this controller
 * reads {@link DeviceEndpointPrincipal#require()} and authorises the device instance per message. 202 = durably stored.
 */
@RestController("e31IngestController")
@RequestMapping("/api/v1/device-ingest")
public class IngestController {

    private final IngestHandler handler;
    private final GatewayService gateways;
    private final RawPayloadStore raw;
    private final EngineSettings settings;
    private final ModuleRegistry modules;
    private final JsonMapper json;

    public IngestController(IngestHandler handler, GatewayService gateways, RawPayloadStore raw, EngineSettings settings, ModuleRegistry modules,
                            JsonMapper json) {
        this.handler = handler;
        this.gateways = gateways;
        this.raw = raw;
        this.settings = settings;
        this.modules = modules;
        this.json = json;
    }

    IngestService.Source source() {
        modules.requireEnabled("E31");
        DeviceEndpointPrincipal p = DeviceEndpointPrincipal.require();
        return new IngestService.Source(p.endpointId(), p.tenantCode() == null ? EngineSettings.DEFAULT_TENANT : p.tenantCode(), p.siteCode(),
                p.deviceInstanceIds(), false);
    }

    static Map<String, String> headers(HttpServletRequest req) {
        Map<String, String> h = new LinkedHashMap<>();
        Enumeration<String> names = req.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String n = names.nextElement();
            String lower = n.toLowerCase(Locale.ROOT);
            if (lower.startsWith("x-") || "idempotency-key".equals(lower)) {
                if (!lower.equals("x-client-cert-sha256") && !lower.equals("x-device-endpoint") && !lower.startsWith("x-forwarded")) {
                    h.put(n, req.getHeader(n));
                }
            }
        }
        return h;
    }

    static ResponseEntity<Object> respond(IngestHandler.Response r) {
        ResponseEntity.BodyBuilder b = ResponseEntity.status(r.status()).contentType(MediaType.parseMediaType(r.contentType()));
        if (r.replayed()) {
            b.header("Idempotent-Replayed", "true");
        }
        return b.body(r.body());
    }

    @PostMapping(value = "/hl7v2", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<Object> hl7(@RequestBody byte[] body, HttpServletRequest req) {
        return respond(handler.hl7(source(), IngestHandler.Headers.of(headers(req)), body));
    }

    @PostMapping(value = "/fhir", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<Object> fhir(@RequestBody byte[] body, HttpServletRequest req) {
        return respond(handler.fhir(source(), IngestHandler.Headers.of(headers(req)), body));
    }

    @PostMapping(value = "/poct1a", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<Object> poct1a(@RequestBody byte[] body, HttpServletRequest req) {
        return respond(handler.poct1a(source(), IngestHandler.Headers.of(headers(req)), body));
    }

    @PostMapping(value = "/files", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<Object> files(@RequestBody byte[] body, HttpServletRequest req) {
        return respond(handler.file(source(), IngestHandler.Headers.of(headers(req)), body, req.getContentType()));
    }

    @PostMapping(value = "/ble", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<Object> ble(@RequestBody byte[] body, HttpServletRequest req) {
        return respond(handler.records(source(), IngestHandler.Headers.of(headers(req)), body, "BLE"));
    }

    @PostMapping(value = "/serial", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<Object> serial(@RequestBody byte[] body, HttpServletRequest req) {
        return respond(handler.records(source(), IngestHandler.Headers.of(headers(req)), body, "SERIAL"));
    }

    @PostMapping(value = "/dicomweb", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<Object> dicomweb(@RequestBody byte[] body, HttpServletRequest req) {
        return respond(handler.dicom(source(), IngestHandler.Headers.of(headers(req)), body));
    }

    @PostMapping(value = "/fallback", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<Object> fallback(@RequestBody byte[] body, HttpServletRequest req) {
        return respond(handler.fallback(source(), IngestHandler.Headers.of(headers(req)), body));
    }

    /** Streamed attachment (PDF, waveform, image, DICOM instance) – never held in memory as a whole; ≤ 50 MB. */
    @PostMapping(value = "/attachments", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<Object> attachment(HttpServletRequest req) throws IOException {
        IngestService.Source s = source();
        String kind = req.getHeader("X-Attachment-Kind") == null ? "RAW" : req.getHeader("X-Attachment-Kind").toUpperCase(Locale.ROOT);
        if (!Set.of("PDF", "WAVEFORM", "IMAGE", "DICOM", "RAW").contains(kind)) {
            throw ApiException.badRequest("validation_failed", "X-Attachment-Kind must be PDF, WAVEFORM, IMAGE, DICOM or RAW");
        }
        String type = req.getContentType() == null ? "application/octet-stream" : req.getContentType().split(";")[0].strip();
        if (!Set.of("application/octet-stream", "application/pdf", "application/dicom", "image/png", "image/jpeg").contains(type)) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type", "Unsupported attachment media type");
        }
        if (req.getContentLengthLong() > settings.maxPayloadBytes()) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "payload_too_large", "The payload exceeds the maximum size");
        }
        RawPayloadStore.ArchiveRef ref = raw.putStream(req.getInputStream(), settings.maxPayloadBytes(), kind, type, s.tenantCode(), s.endpointId());
        String expected = req.getHeader("X-Content-Sha256");
        if (expected != null && !Ids.constantTimeEquals(expected, ref.sha256())) {
            raw.deleteUnreferenced(ref.archiveId());
            throw ApiException.badRequest("file_hash_mismatch", "X-Content-Sha256 does not match the body");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("archiveId", ref.archiveId(), "sha256", ref.sha256(), "sizeBytes", ref.sizeBytes()));
    }

    /** Reconciliation after store-and-forward (≤ 100 items, ≤ 10 MB): items in sequence order, per-item status. */
    @PostMapping(value = "/batch", consumes = MediaType.ALL_VALUE)
    public Map<String, Object> batch(@RequestBody byte[] body) {
        IngestService.Source s = source();
        if (body.length > 10L * 1024 * 1024) {
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
        List<JsonNode> sorted = new ArrayList<>();
        items.forEach(sorted::add);
        sorted.sort(Comparator.comparingLong(n -> n.path("sequence").asLong(Long.MAX_VALUE)));
        List<Map<String, Object>> results = new ArrayList<>();
        for (JsonNode item : sorted) {
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
                if (r.body() instanceof String ack) {
                    result.put("ack", ack);
                } else {
                    result.put("receipt", r.body());
                }
            } catch (ApiException e) {
                result.put("httpStatus", e.status().value());
                ProblemDetail pd = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
                pd.setProperty("code", e.code());
                result.put("problem", Map.of("status", e.status().value(), "code", e.code(), "detail", String.valueOf(pd.getDetail())));
            }
            results.add(result);
        }
        return Map.of("results", results);
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
        return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        source();
        return gateways.config(DeviceEndpointPrincipal.require());
    }

    @PostMapping(value = "/heartbeat", consumes = MediaType.ALL_VALUE)
    public Map<String, Object> heartbeat(@RequestBody byte[] body) {
        source();
        JsonNode hb;
        try {
            hb = json.readTree(body);
        } catch (JacksonException e) {
            throw ApiException.badRequest("validation_failed", "The heartbeat must be JSON");
        }
        return gateways.heartbeat(DeviceEndpointPrincipal.require(), hb == null ? json.createObjectNode() : hb);
    }

    record CommandResult(String status, String detail) {
    }

    @PostMapping("/commands/{commandId}/result")
    public ResponseEntity<Void> commandResult(@PathVariable UUID commandId, @RequestBody CommandResult r) {
        source();
        gateways.commandResult(DeviceEndpointPrincipal.require(), commandId, r.status(), r.detail());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/worklist")
    public List<Map<String, Object>> worklist(@RequestParam String deviceInstanceId, @RequestParam(required = false) LocalDate date) {
        source();
        return gateways.worklist(DeviceEndpointPrincipal.require(), deviceInstanceId, date == null ? LocalDate.now(java.time.ZoneOffset.UTC) : date);
    }

    @GetMapping("/secrets/{ref}")
    public ResponseEntity<Map<String, Object>> secret(@PathVariable String ref) {
        source();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(gateways.secret(DeviceEndpointPrincipal.require(), ref));
    }
}
