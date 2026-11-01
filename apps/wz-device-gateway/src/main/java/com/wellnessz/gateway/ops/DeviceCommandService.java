package com.wellnessz.gateway.ops;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.wellnessz.gateway.config.GatewayProperties;
import com.wellnessz.gateway.connector.Connector;
import com.wellnessz.gateway.connector.db.VendorDbPoller;
import com.wellnessz.gateway.connector.mllp.MllpClient;
import com.wellnessz.gateway.connector.serial.SerialConnector;
import com.wellnessz.gateway.core.CoreClient;
import com.wellnessz.gateway.core.CoreResponse;
import com.wellnessz.gateway.support.Hashes;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Device-bound gateway commands (E31-HLR-08; contract §7 and §16.1). The content comes from the core
 * ({@code GET /device-ingest/commands/{commandId}/outbound}); the target always comes from the local edge configuration:
 * <ul>
 * <li>{@code PUSH_WORKLIST}: transport {@code MLLP} – each HAPI-built ORM^O01 / OML^O21 / ADT^A04 / ADT^A08 to the device's
 * {@code wz.gateway.mllp-outbound} target with its acknowledgement; transport {@code GDT} – the GDT 6302 import files.</li>
 * <li>{@code START_ACQUISITION}: {@code SERIAL} – the driver's start bytes written to the serial connector of the device
 * (serial-over-TCP), optionally waiting for the expected reply; {@code MLLP} – the session order to the device.</li>
 * <li>{@code RETRIEVE_HISTORY}: {@code HL7_QUERY} – QRY^R02 over MLLP, the result frames (ORU / ORF) returned through
 * {@code POST /device-ingest/retrievals/{id}/batch}; {@code VENDOR_DB} – the vendor-database connector's read-only query
 * bounded by the range, one CSV file per row.</li>
 * </ul>
 * Message bodies are held in memory only for the exchange; nothing is logged but counts and acknowledgement codes.
 */
public class DeviceCommandService {

    private static final Logger log = LoggerFactory.getLogger(DeviceCommandService.class);
    static final int BATCH_ITEMS = 100;

    private final CoreClient core;
    private final WorklistService gdt;
    private final List<GatewayProperties.MllpOutbound> targets;
    private final boolean allowPlaintext;
    private final java.util.function.Supplier<List<Connector>> connectors;
    private final JsonMapper json = JsonMapper.builder().build();

    public DeviceCommandService(CoreClient core, WorklistService gdt, List<GatewayProperties.MllpOutbound> targets, boolean allowPlaintext,
                                java.util.function.Supplier<List<Connector>> connectors) {
        this.core = core;
        this.gdt = gdt;
        this.targets = List.copyOf(targets);
        this.allowPlaintext = allowPlaintext;
        this.connectors = connectors;
    }

    // ------------------------------------------------------------------ commands

    public CommandProcessor.Result pushWorklist(String commandId, String deviceInstanceId, java.time.LocalDate date) throws IOException {
        JsonNode out = outbound(commandId);
        String transport = out.path("transport").asString("");
        if ("GDT".equals(transport)) {
            int n = gdt.push(deviceInstanceId, date);
            return CommandProcessor.Result.done(n + " worklist entries written");
        }
        if (!"MLLP".equals(transport)) {
            return CommandProcessor.Result.failed("unsupported_transport");
        }
        return sendAll(deviceInstanceId, out.path("messages"), "worklist");
    }

    public CommandProcessor.Result startAcquisition(String commandId, String deviceInstanceId) throws IOException {
        Optional<SerialConnector> serial = serial(deviceInstanceId);
        Optional<MllpClient> mllp = mllp(deviceInstanceId);
        if (serial.filter(SerialConnector::writable).isEmpty() && mllp.isEmpty()) {
            return CommandProcessor.Result.failed("not_supported_by_connector");
        }
        JsonNode out = outbound(commandId);
        String transport = out.path("transport").asString("");
        if ("SERIAL".equals(transport)) {
            SerialConnector c = serial.filter(SerialConnector::writable).orElse(null);
            if (c == null) {
                return CommandProcessor.Result.failed("not_supported_by_connector");
            }
            byte[] bytes = Base64.getDecoder().decode(out.path("payloadBase64").asString(""));
            Pattern expect = pattern(out.path("expectPattern").asString(null));
            String control = out.path("controlId").asString("");
            try {
                String reply = c.command(bytes, expect, Duration.ofSeconds(10));
                return CommandProcessor.Result.done("start command sent" + (reply.isEmpty() ? "" : "; device ready"),
                        List.of(ack(control, "CA", reply.isEmpty() ? null : "device replied")));
            } catch (IOException e) {
                return CommandProcessor.Result.failed("start_failed", List.of(ack(control, "CE", e.getMessage())));
            }
        }
        if ("MLLP".equals(transport)) {
            if (mllp.isEmpty()) {
                return CommandProcessor.Result.failed("not_supported_by_connector");
            }
            return sendAll(deviceInstanceId, out.path("messages"), "start");
        }
        return CommandProcessor.Result.failed("unsupported_transport");
    }

    public CommandProcessor.Result retrieveHistory(String commandId, String deviceInstanceId) throws IOException {
        JsonNode out = outbound(commandId);
        String retrievalId = out.path("retrievalId").asString("");
        if (!retrievalId.matches("[0-9a-fA-F-]{36}")) {
            return CommandProcessor.Result.failed("invalid_retrieval");
        }
        String method = out.path("method").asString("");
        List<ObjectNode> items = new ArrayList<>();
        if ("HL7_QUERY".equals(method)) {
            MllpClient client = mllp(deviceInstanceId).orElse(null);
            if (client == null) {
                return CommandProcessor.Result.failed("not_supported_by_connector");
            }
            JsonNode q = out.path("messages").path(0);
            List<byte[]> frames;
            try {
                frames = client.query(q.path("er7").asString("").getBytes(StandardCharsets.ISO_8859_1));
            } catch (IOException e) {
                return CommandProcessor.Result.failed("query_failed", List.of(ack(q.path("controlId").asString(""), "AE", e.getClass().getSimpleName())));
            }
            String queryAck = "";
            long seq = 0;
            for (byte[] f : frames) {
                String type = MllpClient.messageType(f);
                if (type.startsWith("ORU") || type.startsWith("ORF")) {
                    ObjectNode item = json.createObjectNode();
                    item.put("kind", "HL7V2");
                    item.put("idempotencyKey", "HQ:" + Hashes.sha256Hex(f));
                    item.put("sequence", ++seq);
                    item.put("contentType", "application/hl7-v2");
                    item.put("body", new String(f, StandardCharsets.ISO_8859_1));
                    items.add(item);
                } else if (queryAck.isEmpty()) {
                    queryAck = MllpClient.parse(f).code();
                }
            }
            if ("AE".equals(queryAck) || "AR".equals(queryAck)) {
                return CommandProcessor.Result.failed("query_rejected", List.of(ack(q.path("controlId").asString(""), queryAck, null)));
            }
        } else if ("VENDOR_DB".equals(method)) {
            VendorDbPoller db = db(deviceInstanceId).orElse(null);
            if (db == null) {
                return CommandProcessor.Result.failed("not_supported_by_connector");
            }
            List<VendorDbPoller.HistoricRow> rows;
            try {
                rows = db.history(Instant.parse(out.path("from").asString()), Instant.parse(out.path("to").asString()));
            } catch (SQLException e) {
                return CommandProcessor.Result.failed("query_failed");
            }
            long seq = 0;
            for (VendorDbPoller.HistoricRow r : rows) {
                ObjectNode item = json.createObjectNode();
                item.put("kind", "FILE");
                item.put("idempotencyKey", r.sha256());
                item.put("sequence", ++seq);
                item.put("contentType", "text/csv; charset=UTF-8");
                item.put("bodyEncoding", "BASE64");
                item.put("body", Base64.getEncoder().encodeToString(r.content()));
                ObjectNode h = item.putObject("headers");
                h.put("X-File-Name", r.fileName());
                h.put("X-File-Format", "CSV");
                h.put("X-File-Sha256", r.sha256());
                h.put("X-Source-Path-Hash", db.sourceHash());
                items.add(item);
            }
        } else {
            return CommandProcessor.Result.failed("unsupported_method");
        }
        int accepted = 0;
        int duplicates = 0;
        for (int i = 0; i < Math.max(1, items.size()); i += BATCH_ITEMS) {
            List<ObjectNode> chunk = items.isEmpty() ? List.of() : items.subList(i, Math.min(items.size(), i + BATCH_ITEMS));
            boolean last = i + BATCH_ITEMS >= items.size();
            if (chunk.isEmpty()) {
                break; // nothing found: the command result closes the retrieval
            }
            ObjectNode body = json.createObjectNode();
            ArrayNode arr = body.putArray("items");
            chunk.forEach(arr::add);
            body.put("last", last);
            CoreResponse r = core.send("POST", "/device-ingest/retrievals/" + retrievalId + "/batch", "application/json", Map.of(),
                    json.writeValueAsBytes(body));
            if (!r.success()) {
                return CommandProcessor.Result.failed("batch_refused_http_" + r.status());
            }
            JsonNode retrieval = json.readTree(r.text()).path("retrieval");
            accepted = retrieval.path("accepted").asInt(accepted);
            duplicates = retrieval.path("duplicates").asInt(duplicates);
        }
        log.info("historic retrieval {}: {} results ({} accepted, {} duplicates)", retrievalId, items.size(), accepted, duplicates);
        return CommandProcessor.Result.done(items.size() + " results returned (" + accepted + " accepted, " + duplicates + " duplicates)");
    }

    // ------------------------------------------------------------------ helpers

    CommandProcessor.Result sendAll(String deviceInstanceId, JsonNode messages, String what) {
        MllpClient client = mllp(deviceInstanceId).orElse(null);
        if (client == null) {
            return CommandProcessor.Result.failed("no_mllp_outbound_target");
        }
        List<Map<String, String>> acks = new ArrayList<>();
        int positive = 0;
        for (JsonNode m : messages) {
            String control = m.path("controlId").asString("");
            try {
                MllpClient.Ack a = client.send(m.path("er7").asString("").getBytes(StandardCharsets.ISO_8859_1));
                acks.add(ack(control, a.code(), a.text()));
                if ("AA".equals(a.code()) || "CA".equals(a.code())) {
                    positive++;
                }
            } catch (IOException e) {
                acks.add(ack(control, "AE", "transport: " + e.getClass().getSimpleName()));
            }
        }
        int total = acks.size();
        String detail = positive + "/" + total + " " + what + " messages acknowledged";
        return positive == total ? CommandProcessor.Result.done(detail, acks) : CommandProcessor.Result.failed(detail, acks);
    }

    JsonNode outbound(String commandId) throws IOException {
        CoreResponse r = core.send("GET", "/device-ingest/commands/" + commandId + "/outbound", null, Map.of(), null);
        if (!r.success()) {
            throw new IOException("outbound content refused: HTTP " + r.status() + " " + r.problemCode().orElse(""));
        }
        return json.readTree(r.text());
    }

    Optional<MllpClient> mllp(String deviceInstanceId) {
        return targets.stream().filter(t -> t.deviceInstanceId() != null && t.deviceInstanceId().equals(deviceInstanceId)).findFirst()
                .map(t -> new MllpClient(t, allowPlaintext));
    }

    Optional<SerialConnector> serial(String deviceInstanceId) {
        return connectors.get().stream().filter(c -> c instanceof SerialConnector && deviceInstanceId != null
                && deviceInstanceId.equals(((SerialConnector) c).deviceInstanceId())).map(c -> (SerialConnector) c).findFirst();
    }

    Optional<VendorDbPoller> db(String deviceInstanceId) {
        return connectors.get().stream().filter(c -> c instanceof VendorDbPoller && deviceInstanceId != null
                && deviceInstanceId.equals(((VendorDbPoller) c).deviceInstanceId())).map(c -> (VendorDbPoller) c).findFirst();
    }

    static Pattern pattern(String regex) {
        if (regex == null || regex.isBlank()) {
            return null;
        }
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    static Map<String, String> ack(String controlId, String code, String text) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("controlId", controlId);
        m.put("code", code);
        if (text != null) {
            m.put("text", text.length() > 120 ? text.substring(0, 120) : text);
        }
        return m;
    }
}
