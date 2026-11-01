package com.wellnessz.e31.web;

import java.io.IOException;
import java.time.LocalDate;
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
import com.wellnessz.e31.outbound.OutboundService;
import com.wellnessz.e31.service.GatewayService;
import com.wellnessz.e31.service.HistoricRetrievalService;
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
    private final BatchIngest batches;
    private final GatewayService gateways;
    private final OutboundService outbound;
    private final HistoricRetrievalService historic;
    private final RawPayloadStore raw;
    private final EngineSettings settings;
    private final ModuleRegistry modules;
    private final JsonMapper json;

    public IngestController(IngestHandler handler, BatchIngest batches, GatewayService gateways, OutboundService outbound,
                            HistoricRetrievalService historic, RawPayloadStore raw, EngineSettings settings, ModuleRegistry modules,
                            JsonMapper json) {
        this.handler = handler;
        this.batches = batches;
        this.gateways = gateways;
        this.outbound = outbound;
        this.historic = historic;
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
        return Map.of("results", batches.process(s, batches.items(body)));
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

    /** @param acks per-message acknowledgements of outbound HL7 / serial messages ({@code controlId}, {@code code} AA|AE|AR|CA|CE|CR) */
    record CommandResult(String status, String detail, List<OutboundService.Ack> acks) {
    }

    @PostMapping("/commands/{commandId}/result")
    public ResponseEntity<Void> commandResult(@PathVariable UUID commandId, @RequestBody CommandResult r) {
        source();
        DeviceEndpointPrincipal p = DeviceEndpointPrincipal.require();
        OutboundService.Command command = outbound.command(p, commandId);
        gateways.commandResult(p, commandId, r.status(), r.detail());
        outbound.results(command, r.status(), r.detail(), r.acks());
        if ("RETRIEVE_HISTORY".equals(command.type())) {
            historic.commandResult(commandId, r.status(), r.detail());
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * What the gateway sends for a command (contract §7): HL7 order / ADT / query messages for MLLP, the start bytes for a serial
     * device, or the transport {@code GDT} / {@code VENDOR_DB} when the gateway produces the content itself.
     */
    @GetMapping("/commands/{commandId}/outbound")
    public ResponseEntity<Map<String, Object>> outbound(@PathVariable UUID commandId) {
        source();
        DeviceEndpointPrincipal p = DeviceEndpointPrincipal.require();
        OutboundService.Command command = outbound.command(p, commandId);
        Map<String, Object> body = "RETRIEVE_HISTORY".equals(command.type()) ? historic.outbound(p, commandId, command.payload())
                : outbound.outbound(p, commandId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    /**
     * Historic results of a {@code RETRIEVE_HISTORY} command (≤ 100 items per call, same item format as {@code /batch});
     * {@code "last": true} closes the retrieval. Items are keyed deterministically (duplicates are never stored twice).
     */
    @PostMapping(value = "/retrievals/{retrievalId}/batch", consumes = MediaType.ALL_VALUE)
    public Map<String, Object> retrievalBatch(@PathVariable UUID retrievalId, @RequestBody byte[] body) {
        IngestService.Source s = source();
        DeviceEndpointPrincipal p = DeviceEndpointPrincipal.require();
        JsonNode items = batches.items(body);
        boolean last;
        try {
            last = json.readTree(body).path("last").asBoolean(false);
        } catch (JacksonException e) {
            throw ApiException.badRequest("validation_failed", "The batch must be JSON");
        }
        HistoricRetrievalService.Prepared prepared = historic.prepare(p, retrievalId, items, batches::payload);
        List<Map<String, Object>> results = new java.util.ArrayList<>(prepared.decided());
        if (!prepared.items().isEmpty()) {
            results.addAll(batches.process(s, prepared.items()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("results", results);
        out.put("retrieval", historic.recordBatch(p, retrievalId, results, last));
        return out;
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
