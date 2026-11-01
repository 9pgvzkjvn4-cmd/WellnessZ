package com.wellnessz.e31.service;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.wellnessz.casekernel.device.DeviceCatalogueLookup;
import com.wellnessz.casekernel.device.DeviceEndpointPrincipal;
import com.wellnessz.casekernel.device.DeviceSpis;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e31.driver.DriverPackage;
import com.wellnessz.e31.driver.DriverRuntime;
import com.wellnessz.e31.outbound.Hl7OutboundBuilder;
import com.wellnessz.e31.support.EngineSettings;
import com.wellnessz.e31.support.Ids;
import com.wellnessz.security.audit.AuditService;

import ca.uhn.hl7v2.HL7Exception;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Historic retrieval (E31-HLR-08 "historic retrieval", IHE RDQ-style / vendor query; contract §12 and §7): a device administrator
 * or integration tester asks for the results a device or data manager holds for a time range; the engine queues the gateway
 * command {@code RETRIEVE_HISTORY}; the gateway runs the query ({@code HL7_QUERY}: an HAPI-built {@code QRY^R02} over MLLP;
 * {@code VENDOR_DB}: the vendor-database connector's read-only query bounded by the range) and returns the results through
 * {@code POST /device-ingest/retrievals/{id}/batch}. Every item gets a <b>deterministic</b> idempotency key
 * {@code HR:<UUIDv5(device, normalised content)>} – the HL7 envelope fields that change per transmission (MSH-7, MSH-10) are
 * removed before hashing – and an item whose exact bytes were already ingested for the device is answered as a duplicate
 * without being stored again: overlapping retrievals and results the device had already pushed never create a second
 * observation.
 */
@Service("e31HistoricRetrievalService")
public class HistoricRetrievalService {

    static final Duration MAX_RANGE = Duration.ofDays(31);

    private final DeviceSpis spis;
    private final DriverRuntime drivers;
    private final GatewayService gateways;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final Clock clock;
    private final Hl7OutboundBuilder hl7 = new Hl7OutboundBuilder();

    public HistoricRetrievalService(DeviceSpis spis, DriverRuntime drivers, GatewayService gateways, AuditService audit, JdbcTemplate jdbc,
                                    JsonMapper json, Clock clock) {
        this.spis = spis;
        this.drivers = drivers;
        this.gateways = gateways;
        this.audit = audit;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    /** {@code POST /device-engine/historic-retrievals}. */
    public Map<String, Object> request(String actor, String deviceInstanceId, Instant from, Instant to) {
        Instant now = clock.instant();
        if (from == null || to == null || !from.isBefore(to)) {
            throw ApiException.badRequest("validation_failed", "from must be before to");
        }
        if (to.isAfter(now.plusSeconds(60)) || Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw ApiException.badRequest("validation_failed", "The range must lie in the past and span at most 31 days");
        }
        DeviceCatalogueLookup.DeviceInstance i = spis.catalogue().instance(deviceInstanceId).filter(DeviceCatalogueLookup.DeviceInstance::published)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "device_not_published", "The device instance is not published"));
        if (i.endpointId() == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "historic_retrieval_not_supported", "The device is not served by a gateway");
        }
        DriverPackage pkg = drivers.forInstance(i, null);
        String method = method(pkg);
        if (method == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "historic_retrieval_not_supported",
                    "The device driver declares no historic retrieval");
        }
        UUID id = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("retrievalId", id.toString());
        payload.put("method", method);
        payload.put("from", from.toString());
        payload.put("to", to.toString());
        UUID command = gateways.queueCommand(i.endpointId(), "RETRIEVE_HISTORY", i.deviceInstanceId(), payload, actor);
        jdbc.update("""
                insert into deviceint.historic_retrieval (id, tenant_code, device_instance_id, endpoint_id, from_at, to_at, method, status, command_id,
                    requested_by, requested_at) values (?, ?, ?, ?, ?, ?, ?, 'REQUESTED', ?, ?, ?)""", id,
                i.tenantCode() == null ? EngineSettings.DEFAULT_TENANT : i.tenantCode(), i.deviceInstanceId(), i.endpointId(), Timestamp.from(from),
                Timestamp.from(to), method, command, actor, Timestamp.from(now));
        audit.record("DEVICE_HISTORIC_RETRIEVAL", "DEVICE_INSTANCE", i.deviceInstanceId(), AuditService.Outcome.SUCCESS,
                method + " " + from + "/" + to);
        return get(id);
    }

    /** HL7_QUERY for HL7 v2 drivers, VENDOR_DB for vendor-database drivers – only when the driver declares the capability. */
    static String method(DriverPackage pkg) {
        if (!pkg.capabilities().historicRetrieval()) {
            return null;
        }
        String declared = pkg.protocol() == null ? null : pkg.protocol().path("historicQuery").path("method").asString(null);
        if (declared != null) {
            return List.of("HL7_QUERY", "VENDOR_DB").contains(declared) ? declared : null;
        }
        if ("HL7V2".equals(pkg.adapter())) {
            return "HL7_QUERY";
        }
        if (pkg.protocol() != null && "DB_QUERY".equals(pkg.protocol().path("source").asString(""))) {
            return "VENDOR_DB";
        }
        return null;
    }

    public Map<String, Object> get(UUID id) {
        return jdbc.queryForList("""
                select id, device_instance_id as "deviceInstanceId", endpoint_id as "endpointId", from_at as "from", to_at as "to", method, status,
                       command_id as "commandId", requested_by as "requestedBy", requested_at as "requestedAt", completed_at as "completedAt", received,
                       accepted, duplicates, failed, detail from deviceint.historic_retrieval where id = ?""", id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Historic retrieval"));
    }

    public List<Map<String, Object>> list(String deviceInstanceId, int limit) {
        return jdbc.queryForList("""
                select id, device_instance_id as "deviceInstanceId", from_at as "from", to_at as "to", method, status, requested_by as "requestedBy",
                       requested_at as "requestedAt", completed_at as "completedAt", received, accepted, duplicates, failed, detail
                  from deviceint.historic_retrieval where (?::varchar is null or device_instance_id = ?) order by requested_at desc limit ?""",
                deviceInstanceId, deviceInstanceId, Math.max(1, Math.min(200, limit)));
    }

    // ------------------------------------------------------------------ gateway side

    record Retrieval(UUID id, String deviceInstanceId, String endpointId, Instant from, Instant to, String method, String status, UUID commandId) {
    }

    Retrieval retrieval(DeviceEndpointPrincipal p, UUID id) {
        return jdbc.query("""
                select id, device_instance_id, endpoint_id, from_at, to_at, method, status, command_id from deviceint.historic_retrieval
                 where id = ? and endpoint_id = ?""", (rs, n) -> new Retrieval((UUID) rs.getObject(1), rs.getString(2), rs.getString(3),
                rs.getTimestamp(4).toInstant(), rs.getTimestamp(5).toInstant(), rs.getString(6), rs.getString(7), (UUID) rs.getObject(8)),
                id, p.endpointId()).stream().findFirst().orElseThrow(() -> ApiException.notFound("Historic retrieval"));
    }

    /** What the gateway sends for a {@code RETRIEVE_HISTORY} command ({@code GET /device-ingest/commands/{id}/outbound}). */
    public Map<String, Object> outbound(DeviceEndpointPrincipal p, UUID commandId, JsonNode payload) {
        UUID id = UUID.fromString(payload.path("retrievalId").asString());
        Retrieval r = retrieval(p, id);
        DeviceCatalogueLookup.DeviceInstance i = gateways.instanceFor(p, r.deviceInstanceId());
        jdbc.update("update deviceint.historic_retrieval set status = 'SENT' where id = ? and status = 'REQUESTED'", id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("commandId", commandId);
        out.put("retrievalId", id);
        out.put("method", r.method());
        out.put("from", r.from().toString());
        out.put("to", r.to().toString());
        if ("HL7_QUERY".equals(r.method())) {
            DriverPackage pkg = drivers.forInstance(i, null);
            int max = pkg.protocol() == null ? 100 : Math.max(1, Math.min(1000, pkg.protocol().path("historicQuery").path("maxResults").asInt(100)));
            String control = "WZQ" + id.toString().replace("-", "").substring(0, 12).toUpperCase(java.util.Locale.ROOT);
            try {
                Object msh = i.parameters().get("msh");
                Map<?, ?> m = msh instanceof Map<?, ?> x ? x : Map.of();
                Hl7OutboundBuilder.Header h = new Hl7OutboundBuilder.Header(str(m.get("receivingApplication"), "WELLNESSZ"),
                        str(m.get("receivingFacility"), null), str(m.get("sendingApplication"), "DEVICE"), str(m.get("sendingFacility"), null));
                out.put("transport", "MLLP");
                out.put("messages", List.of(Map.of("controlId", control, "messageType", "QRY^R02",
                        "er7", hl7.query(h, i.serial(), r.from(), r.to(), max, id.toString().substring(0, 8), control, clock.instant()))));
            } catch (HL7Exception e) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "outbound_build_failed", "The query could not be built");
            }
        } else {
            out.put("transport", "VENDOR_DB");
            out.put("messages", List.of());
        }
        return out;
    }

    /**
     * Prepares a result batch: every item gets the deterministic key and the device instance; items whose exact bytes were already
     * ingested for the device are answered as duplicates here (they are not stored again).
     *
     * @return the items to ingest and the duplicate results already decided
     */
    public Prepared prepare(DeviceEndpointPrincipal p, UUID retrievalId, JsonNode items, java.util.function.Function<JsonNode, byte[]> body) {
        Retrieval r = retrieval(p, retrievalId);
        if ("COMPLETED".equals(r.status()) || "FAILED".equals(r.status())) {
            throw ApiException.conflict("retrieval_closed", "The retrieval is already closed");
        }
        ArrayNode toIngest = json.createArrayNode();
        List<Map<String, Object>> decided = new java.util.ArrayList<>();
        for (JsonNode item : items) {
            byte[] bytes = body.apply(item);
            String sha = Ids.sha256Hex(bytes);
            String key = "HR:" + deterministicKey(r.deviceInstanceId(), item.path("kind").asString(""), bytes);
            Integer seen = jdbc.queryForObject("select count(*) from deviceint.message where device_instance_id = ? and sha256 = ?", Integer.class,
                    r.deviceInstanceId(), sha);
            if (seen != null && seen > 0) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("idempotencyKey", key);
                d.put("httpStatus", 200);
                d.put("receipt", Map.of("status", "DUPLICATE", "reason", "already_ingested"));
                decided.add(d);
                continue;
            }
            ObjectNode copy = item.isObject() ? (ObjectNode) item.deepCopy() : json.createObjectNode();
            copy.put("idempotencyKey", key);
            ObjectNode headers = copy.has("headers") && copy.get("headers").isObject() ? (ObjectNode) copy.get("headers") : copy.putObject("headers");
            headers.put("X-Device-Instance", r.deviceInstanceId());
            headers.put("X-Retrieval-Id", retrievalId.toString());
            toIngest.add(copy);
        }
        jdbc.update("update deviceint.historic_retrieval set status = 'RECEIVING' where id = ? and status in ('REQUESTED','SENT')", retrievalId);
        return new Prepared(toIngest, decided);
    }

    /** Items to ingest and the results already decided (duplicates). */
    public record Prepared(ArrayNode items, List<Map<String, Object>> decided) {
    }

    /** Counts a processed batch into the retrieval; {@code last} closes it. */
    public Map<String, Object> recordBatch(DeviceEndpointPrincipal p, UUID retrievalId, List<Map<String, Object>> results, boolean last) {
        retrieval(p, retrievalId);
        int accepted = 0;
        int duplicates = 0;
        int failed = 0;
        for (Map<String, Object> res : results) {
            int status = ((Number) res.getOrDefault("httpStatus", 500)).intValue();
            String receiptStatus = res.get("receipt") instanceof Map<?, ?> m ? String.valueOf(m.get("status")) : null;
            String ack = res.get("ack") instanceof String a ? a : null;
            if ("DUPLICATE".equals(receiptStatus) || Boolean.TRUE.equals(res.get("replayed"))) {
                duplicates++;
            } else if (status >= 200 && status < 300 && (ack == null || ack.contains("MSA|AA") || ack.contains("V=\"AA\""))) {
                accepted++;
            } else {
                failed++;
            }
        }
        Instant now = clock.instant();
        jdbc.update("""
                update deviceint.historic_retrieval set received = received + ?, accepted = accepted + ?, duplicates = duplicates + ?, failed = failed + ?,
                    status = case when ? then 'COMPLETED' else status end, completed_at = case when ? then ? else completed_at end where id = ?""",
                results.size(), accepted, duplicates, failed, last, last, Timestamp.from(now), retrievalId);
        return get(retrievalId);
    }

    /** Command result of {@code RETRIEVE_HISTORY}: DONE completes, FAILED fails the retrieval (with the gateway's reason code). */
    public void commandResult(UUID commandId, String status, String detail) {
        Instant now = clock.instant();
        if ("DONE".equals(status)) {
            jdbc.update("update deviceint.historic_retrieval set status = 'COMPLETED', completed_at = coalesce(completed_at, ?), detail = ? "
                    + "where command_id = ? and status <> 'FAILED'", Timestamp.from(now), cap(detail), commandId);
        } else {
            jdbc.update("update deviceint.historic_retrieval set status = 'FAILED', completed_at = ?, detail = ? where command_id = ?",
                    Timestamp.from(now), cap(detail), commandId);
        }
    }

    /**
     * Deterministic key of a historic item: UUIDv5 over the device and the content; for HL7 v2 the per-transmission MSH fields
     * (MSH-7 date/time of message, MSH-10 control id) are blanked first.
     */
    public static String deterministicKey(String deviceInstanceId, String kind, byte[] body) {
        String content = new String(body, StandardCharsets.ISO_8859_1);
        if ("HL7V2".equals(kind)) {
            content = normaliseHl7(content);
        }
        return Ids.uuidV5("historic:" + deviceInstanceId + ":" + kind + ":" + Ids.sha256Hex(content.getBytes(StandardCharsets.ISO_8859_1))).toString();
    }

    static String normaliseHl7(String er7) {
        String[] segments = er7.replace("\r\n", "\r").replace('\n', '\r').strip().split("\r");
        StringBuilder sb = new StringBuilder();
        for (String seg : segments) {
            if (seg.startsWith("MSH") && seg.length() > 3) {
                String sep = String.valueOf(seg.charAt(3));
                String[] f = seg.split(java.util.regex.Pattern.quote(sep), -1);
                // f[0]=MSH, f[1]=encoding chars (MSH-2), f[6]=MSH-7, f[9]=MSH-10
                if (f.length > 6) {
                    f[6] = "";
                }
                if (f.length > 9) {
                    f[9] = "";
                }
                seg = String.join(sep, f);
            }
            sb.append(seg).append('\r');
        }
        return sb.toString();
    }

    static String str(Object o, String fallback) {
        return o == null || String.valueOf(o).isBlank() ? fallback : String.valueOf(o);
    }

    static String cap(String s) {
        return s == null ? null : s.length() > 200 ? s.substring(0, 200) : s;
    }
}
