package com.wellnessz.e31.outbound;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.wellnessz.casekernel.device.DeviceCaptureSessions;
import com.wellnessz.casekernel.device.DeviceCatalogueLookup;
import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceEndpointPrincipal;
import com.wellnessz.casekernel.device.DeviceSpis;
import com.wellnessz.casekernel.device.DeviceWorklistSource;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e31.driver.DriverPackage;
import com.wellnessz.e31.driver.DriverRuntime;
import com.wellnessz.e31.service.GatewayService;
import com.wellnessz.e31.support.EngineSettings;

import ca.uhn.hl7v2.HL7Exception;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Outbound device messages behind the gateway commands (E31-HLR-08, E31-BL-004, E31-BL-016, E31-BL-019; contract §7 and §16.1).
 * The gateway fetches what to send with {@code GET /device-ingest/commands/{commandId}/outbound} and reports one acknowledgement
 * per message with the command result:
 * <ul>
 * <li>{@code PUSH_WORKLIST} – drivers with {@code worklistPush HL7_ORM | HL7_ADT}: one HAPI-built {@code ORM^O01},
 * {@code OML^O21}, {@code ADT^A04} or {@code ADT^A08} per minimised worklist entry (E03 orders, E14 appointments), sent over
 * MLLP by the gateway; {@code GDT_6302} keeps the gateway's GDT import file (transport {@code GDT}).</li>
 * <li>{@code START_ACQUISITION} – the driver's {@code protocol.startCommand}: {@code SERIAL} bytes to the serial connector, or
 * {@code MLLP} – the order for the capture session's employee (the device starts the test with the details on screen).</li>
 * <li>{@code RETRIEVE_HISTORY} – handed to {@link com.wellnessz.e31.service.HistoricRetrievalService}.</li>
 * </ul>
 * Only control ids, message types, order references and acknowledgement codes are stored – never a message body.
 */
@Service("e31OutboundService")
public class OutboundService {

    static final Set<String> POSITIVE = Set.of("AA", "CA");
    static final Set<String> NEGATIVE = Set.of("AE", "AR", "CE", "CR");

    private final DeviceSpis spis;
    private final DriverRuntime drivers;
    private final GatewayService gateways;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final Clock clock;
    private final Hl7OutboundBuilder hl7 = new Hl7OutboundBuilder();

    public OutboundService(DeviceSpis spis, DriverRuntime drivers, GatewayService gateways, JdbcTemplate jdbc, JsonMapper json, Clock clock) {
        this.spis = spis;
        this.drivers = drivers;
        this.gateways = gateways;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    /** A queued / sent command of the calling endpoint. */
    public record Command(UUID id, String endpointId, String type, String deviceInstanceId, JsonNode payload, String status) {
    }

    public Command command(DeviceEndpointPrincipal p, UUID commandId) {
        return jdbc.query("select id, endpoint_id, type, device_instance_id, payload, status from deviceint.gateway_command where id = ? and endpoint_id = ?",
                (rs, i) -> new Command((UUID) rs.getObject(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5) == null ? json.createObjectNode() : json.readTree(rs.getString(5)), rs.getString(6)), commandId, p.endpointId())
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Command"));
    }

    // ------------------------------------------------------------------ what the gateway sends

    /** {@code GET /device-ingest/commands/{commandId}/outbound}. */
    public Map<String, Object> outbound(DeviceEndpointPrincipal p, UUID commandId) {
        Command c = command(p, commandId);
        if ("DONE".equals(c.status()) || "FAILED".equals(c.status())) {
            throw ApiException.conflict("command_completed", "The command is already completed");
        }
        DeviceCatalogueLookup.DeviceInstance instance = gateways.instanceFor(p, c.deviceInstanceId());
        DriverPackage pkg = drivers.forInstance(instance, null);
        return switch (c.type()) {
            case "PUSH_WORKLIST" -> worklist(p, c, instance, pkg);
            case "START_ACQUISITION" -> start(p, c, instance, pkg);
            default -> throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "no_outbound_content", "The command carries no outbound content");
        };
    }

    Map<String, Object> worklist(DeviceEndpointPrincipal p, Command c, DeviceCatalogueLookup.DeviceInstance i, DriverPackage pkg) {
        String mode = pkg.capabilities().worklistPush();
        if ("GDT_6302".equals(mode) || "VENDOR_IMPORT_FILE".equals(mode)) {
            return Map.of("commandId", c.id(), "transport", "GDT", "messages", List.of());
        }
        String type = worklistMessageType(i, pkg);
        if (type == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "worklist_not_supported", "The device driver declares no HL7 worklist push");
        }
        String date = c.payload().path("date").asString(null);
        String orderRef = c.payload().path("orderRef").asString(null);
        List<Map<String, Object>> entries = gateways.worklist(p, i.deviceInstanceId(),
                date == null ? LocalDate.now(clock.withZone(ZoneOffset.UTC)) : LocalDate.parse(date));
        List<Map<String, Object>> messages = new ArrayList<>();
        Instant now = clock.instant();
        for (Map<String, Object> e : entries) {
            String ref = (String) e.get("orderRef");
            if (orderRef != null && !orderRef.equals(ref)) {
                continue;
            }
            Hl7OutboundBuilder.Order o = new Hl7OutboundBuilder.Order(ref, (String) e.get("employeeId"), (String) e.get("displayName"),
                    e.get("dateOfBirth") instanceof LocalDate d ? d : null, (String) e.get("sex"),
                    e.get("scheduledAt") instanceof Instant t ? t : null, firstCategory(i), c.payload().path("sessionId").asString(null));
            messages.add(message(c, i, type, o, now));
        }
        return Map.of("commandId", c.id(), "transport", "MLLP", "messages", messages);
    }

    Map<String, Object> start(DeviceEndpointPrincipal p, Command c, DeviceCatalogueLookup.DeviceInstance i, DriverPackage pkg) {
        JsonNode start = startCommand(pkg);
        if (start == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "start_not_supported", "The device driver declares no start command");
        }
        Instant now = clock.instant();
        String transport = start.path("transport").asString("");
        if ("SERIAL".equals(transport)) {
            String encoding = start.path("encoding").asString("TEXT").toUpperCase(Locale.ROOT);
            String payload = start.path("payload").asString("");
            byte[] bytes = "HEX".equals(encoding) ? HexFormat.of().parseHex(payload) : payload.getBytes(StandardCharsets.ISO_8859_1);
            String control = controlId(c.id(), 0);
            record(c, i, "SERIAL", "START", control, null, now);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("commandId", c.id());
            out.put("transport", "SERIAL");
            out.put("controlId", control);
            out.put("payloadBase64", Base64.getEncoder().encodeToString(bytes));
            out.put("expectPattern", start.path("expectPattern").asString(null));
            return out;
        }
        if ("MLLP".equals(transport)) {
            String type = start.path("messageType").asString("ORM^O01");
            Hl7OutboundBuilder.Order o = sessionOrder(c, i);
            return Map.of("commandId", c.id(), "transport", "MLLP", "messages", List.of(message(c, i, type, o, now)));
        }
        throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "start_not_supported", "Unsupported start transport");
    }

    /** The capture session's employee as an order (details from today's worklist when the order or employee is on it). */
    Hl7OutboundBuilder.Order sessionOrder(Command c, DeviceCatalogueLookup.DeviceInstance i) {
        Optional<DeviceCaptureSessions.SessionBinding> s = spis.sessions().activeSession(i.deviceInstanceId(), clock.instant());
        if (s.isEmpty() || s.get().employeeId() == null) {
            throw ApiException.conflict("session_not_active", "No active capture session on the device");
        }
        DeviceCaptureSessions.SessionBinding b = s.get();
        String ref = b.orderRef() != null ? b.orderRef() : "SES-" + b.sessionId().toString().substring(0, 8);
        for (DeviceCodes.Category cat : i.categories()) {
            for (DeviceWorklistSource src : spis.worklists()) {
                for (DeviceWorklistSource.WorklistEntry e : src.worklist(i.siteCode(), LocalDate.now(clock.withZone(ZoneOffset.UTC)), cat)) {
                    if (b.employeeId().equals(e.employeeId()) && (b.orderRef() == null || b.orderRef().equals(e.orderRef()))) {
                        Set<String> needed = gateways.worklistFields(i);
                        return new Hl7OutboundBuilder.Order(ref, e.employeeId(), e.displayName(), needed.contains("dateOfBirth") ? e.dateOfBirth() : null,
                                needed.contains("sex") ? e.sex() : null, e.scheduledAt(), cat.name(), b.sessionId().toString());
                    }
                }
            }
        }
        return new Hl7OutboundBuilder.Order(ref, b.employeeId(), null, null, null, null, firstCategory(i), b.sessionId().toString());
    }

    Map<String, Object> message(Command c, DeviceCatalogueLookup.DeviceInstance i, String type, Hl7OutboundBuilder.Order o, Instant now) {
        Integer sent = jdbc.queryForObject("select count(*) from deviceint.outbound_message where command_id = ?", Integer.class, c.id());
        int index = sent == null ? 0 : sent;
        String control = controlId(c.id(), index);
        String er7;
        try {
            er7 = hl7.order(type, header(i), o, control, now);
        } catch (HL7Exception e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "outbound_build_failed", "The outbound message could not be built");
        }
        record(c, i, "MLLP", type, control, o.orderRef(), now);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("controlId", control);
        m.put("messageType", type);
        m.put("orderRef", o.orderRef());
        m.put("er7", er7);
        return m;
    }

    void record(Command c, DeviceCatalogueLookup.DeviceInstance i, String transport, String type, String control, String orderRef, Instant now) {
        String session = c.payload().path("sessionId").asString(null);
        jdbc.update("""
                insert into deviceint.outbound_message (id, tenant_code, command_id, command_type, endpoint_id, device_instance_id, session_id, order_ref,
                    transport, message_type, control_id, status, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SENT', ?, ?)
                on conflict (command_id, control_id) do nothing""", UUID.randomUUID(),
                i.tenantCode() == null ? EngineSettings.DEFAULT_TENANT : i.tenantCode(), c.id(), c.type(), c.endpointId(), i.deviceInstanceId(),
                uuidOrNull(session), orderRef, transport, type, control, Timestamp.from(now), Timestamp.from(now));
    }

    // ------------------------------------------------------------------ acknowledgements

    /**
     * Command result with per-message acknowledgements: {@code AA}/{@code CA} → ACKED, {@code AE}/{@code AR}/{@code CE}/{@code CR}
     * → REJECTED; messages without an acknowledgement of a FAILED command → FAILED. A GDT push is recorded as one message.
     */
    public void results(Command c, String status, String detail, List<Ack> acks) {
        Instant now = clock.instant();
        for (Ack a : acks == null ? List.<Ack>of() : acks) {
            if (a.controlId() == null || a.code() == null) {
                continue;
            }
            String code = a.code().toUpperCase(Locale.ROOT);
            String st = POSITIVE.contains(code) ? "ACKED" : NEGATIVE.contains(code) ? "REJECTED" : "FAILED";
            jdbc.update("update deviceint.outbound_message set status = ?, ack_code = ?, detail = ?, updated_at = ? where command_id = ? and control_id = ?",
                    st, code.length() > 4 ? code.substring(0, 4) : code, cap(a.text()), Timestamp.from(now), c.id(), a.controlId());
        }
        if ("FAILED".equals(status)) {
            jdbc.update("update deviceint.outbound_message set status = 'FAILED', detail = coalesce(detail, ?), updated_at = ? where command_id = ? and status = 'SENT'",
                    cap(detail), Timestamp.from(now), c.id());
        }
        if ("PUSH_WORKLIST".equals(c.type()) && c.deviceInstanceId() != null) {
            Integer n = jdbc.queryForObject("select count(*) from deviceint.outbound_message where command_id = ?", Integer.class, c.id());
            if (n != null && n == 0 && (detail == null || detail.contains("worklist entries written") || "FAILED".equals(status))) {
                DeviceCatalogueLookup.DeviceInstance i = spis.catalogue().instance(c.deviceInstanceId()).orElse(null);
                if (i != null) {
                    record(c, i, "GDT", "GDT^6302", controlId(c.id(), 0), c.payload().path("orderRef").asString(null), now);
                    jdbc.update("update deviceint.outbound_message set status = ?, detail = ?, updated_at = ? where command_id = ?",
                            "DONE".equals(status) ? "ACKED" : "FAILED", cap(detail), Timestamp.from(now), c.id());
                }
            }
        }
    }

    /** One acknowledgement reported by the gateway. */
    public record Ack(String controlId, String code, String text) {
    }

    // ------------------------------------------------------------------ status for the capture panel / operations

    public List<Map<String, Object>> list(UUID sessionId, String deviceInstanceId, int limit) {
        if (sessionId == null && (deviceInstanceId == null || deviceInstanceId.isBlank())) {
            throw ApiException.badRequest("validation_failed", "sessionId or deviceInstanceId is required");
        }
        int max = Math.max(1, Math.min(200, limit));
        return sessionId != null
                ? jdbc.queryForList(OUTBOUND_COLUMNS + " where session_id = ? order by created_at desc limit ?", sessionId, max)
                : jdbc.queryForList(OUTBOUND_COLUMNS + " where device_instance_id = ? order by created_at desc limit ?", deviceInstanceId, max);
    }

    static final String OUTBOUND_COLUMNS = """
                select id, command_id as "commandId", command_type as "commandType", device_instance_id as "deviceInstanceId", session_id as "sessionId",
                       order_ref as "orderRef", transport, message_type as "messageType", control_id as "controlId", status, ack_code as "ackCode",
                       detail, created_at as "createdAt", updated_at as "updatedAt"
                  from deviceint.outbound_message""";

    /** Pending (not yet fetched) commands of a session – shown as QUEUED until the gateway picks them up. */
    public List<Map<String, Object>> pendingForSession(UUID sessionId) {
        return jdbc.queryForList("""
                select id as "commandId", type as "commandType", device_instance_id as "deviceInstanceId", status, detail, created_at as "createdAt"
                  from deviceint.gateway_command where payload like ? and type in ('PUSH_WORKLIST','START_ACQUISITION') order by created_at desc limit 20""",
                "%\"sessionId\":\"" + sessionId + "\"%");
    }

    // ------------------------------------------------------------------ helpers

    /** P4 {@code worklist.messageType} of the instance, else the driver's {@code protocol.worklist.messageType}, else by capability. */
    static String worklistMessageType(DeviceCatalogueLookup.DeviceInstance i, DriverPackage pkg) {
        Object wl = i.parameters().get("worklist");
        if (wl instanceof Map<?, ?> m && m.get("messageType") != null && Hl7OutboundBuilder.ORDER_TYPES.contains(String.valueOf(m.get("messageType")))) {
            return String.valueOf(m.get("messageType"));
        }
        String declared = pkg.protocol() == null ? null : pkg.protocol().path("worklist").path("messageType").asString(null);
        if (declared != null && Hl7OutboundBuilder.ORDER_TYPES.contains(declared)) {
            return declared;
        }
        return switch (pkg.capabilities().worklistPush()) {
            case "HL7_ORM" -> "ORM^O01";
            case "HL7_ADT" -> "ADT^A04";
            default -> null;
        };
    }

    /** The driver's start command ({@code protocol.startCommand}) when it starts from the form, else {@code null}. */
    public static JsonNode startCommand(DriverPackage pkg) {
        if (!pkg.capabilities().startFromForm() || pkg.protocol() == null) {
            return null;
        }
        JsonNode s = pkg.protocol().get("startCommand");
        return s == null || !s.isObject() || !Set.of("SERIAL", "MLLP").contains(s.path("transport").asString("")) ? null : s;
    }

    static Hl7OutboundBuilder.Header header(DeviceCatalogueLookup.DeviceInstance i) {
        Object msh = i.parameters().get("msh");
        Map<?, ?> m = msh instanceof Map<?, ?> x ? x : Map.of();
        // inbound the device is the sender and WELLNESSZ the receiver; outbound the roles are swapped
        return new Hl7OutboundBuilder.Header(str(m.get("receivingApplication"), "WELLNESSZ"), str(m.get("receivingFacility"), null),
                str(m.get("sendingApplication"), "DEVICE"), str(m.get("sendingFacility"), null));
    }

    static String firstCategory(DeviceCatalogueLookup.DeviceInstance i) {
        return i.categories().stream().map(Enum::name).sorted().findFirst().orElse(null);
    }

    static String controlId(UUID commandId, int index) {
        return "WZ" + commandId.toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT) + "-" + index;
    }

    static String str(Object o, String fallback) {
        return o == null || String.valueOf(o).isBlank() ? fallback : String.valueOf(o);
    }

    static String cap(String s) {
        return s == null ? null : s.length() > 200 ? s.substring(0, 200) : s;
    }

    static UUID uuidOrNull(String s) {
        try {
            return s == null ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
