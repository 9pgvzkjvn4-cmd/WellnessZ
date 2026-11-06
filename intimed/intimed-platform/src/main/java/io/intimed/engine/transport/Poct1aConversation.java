package io.intimed.engine.transport;

import io.intimed.engine.parse.ParseException;
import io.intimed.engine.parse.XmlParser;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * POCT1-A2 (CLSI POCT1-A2) device ↔ observation-reviewer conversation (IM-E01-BL-007, protocol part) as a
 * transport-independent state machine on the data-manager side. It consumes the device's XML messages and produces the
 * manager's replies; collected OBS.R01 messages are exactly what {@link XmlParser}("POCT1A") parses with the driver
 * grammar.
 *
 * <pre>
 *   AWAIT_HELLO ──HEL.R01──▶ (ACK AA) AWAIT_STATUS ──DST.R01──▶ (ACK AA [+ REQ.R01 ROBS when new observations]) READY
 *   READY ──OBS.R01──▶ (ACK AA, collect) IN_TOPIC ──OBS.R01…──▶ IN_TOPIC ──EOT.R01──▶ (ACK AA + END.R01) TERMINATED
 *   any   ──END.R01──▶ TERMINATED            any unexpected / malformed message ──▶ ACK AE (state unchanged)
 * </pre>
 * Every device message must carry HDR.message_type, HDR.control_id and HDR.version_id; a message whose control id was
 * already acknowledged is acknowledged again but not processed twice (retransmission after a lost ACK). Message size is
 * limited (SC-12) and parsing is XXE-hardened ({@link XmlParser#secureParse}).
 */
public final class Poct1aConversation {
    public enum State { AWAIT_HELLO, AWAIT_STATUS, READY, IN_TOPIC, TERMINATED }

    public static final int MAX_MESSAGE = 512 * 1024;
    public static final int MAX_OBSERVATIONS = 10_000;

    private State state = State.AWAIT_HELLO;
    private final List<String> observations = new ArrayList<>();
    private final Set<String> seenControlIds = new LinkedHashSet<>();
    private final List<String> errors = new ArrayList<>();
    private int ownControlId = 1000;
    private String deviceId = "";
    private int announcedObservations = -1;

    public State state() { return state; }
    public List<String> observations() { return List.copyOf(observations); }
    public List<String> errors() { return List.copyOf(errors); }
    public String deviceId() { return deviceId; }
    public int announcedObservations() { return announcedObservations; }

    /** Processes one device message and returns the manager's replies (in order). */
    public List<String> onMessage(String xml) {
        List<String> out = new ArrayList<>();
        if (xml == null || xml.length() > MAX_MESSAGE) { out.add(ack("AE", "", "message missing or larger than " + MAX_MESSAGE + " bytes")); errors.add("oversize"); return out; }
        Document doc;
        try { doc = XmlParser.secureParse(xml.strip()); } catch (ParseException e) { out.add(ack("AE", "", "malformed XML")); errors.add("malformed"); return out; }
        Element root = doc.getDocumentElement();
        String type = attr(root, "HDR.message_type");
        String ctl = attr(root, "HDR.control_id");
        String version = attr(root, "HDR.version_id");
        if (type.isEmpty() || ctl.isEmpty() || version.isEmpty() || !root.getNodeName().equals(type)) {
            out.add(ack("AE", ctl, "header incomplete or message type does not match the root element"));
            errors.add("header");
            return out;
        }
        if (state == State.TERMINATED) { out.add(ack("AE", ctl, "conversation terminated")); errors.add("after-end"); return out; }
        if ("ACK.R01".equals(type)) return out; // device acknowledging our REQ/END – nothing to answer
        if (!seenControlIds.add(ctl)) { out.add(ack("AA", ctl, null)); return out; } // retransmission: re-ack, do not process again
        switch (type) {
            case "HEL.R01" -> {
                if (state != State.AWAIT_HELLO) return reject(out, ctl, type);
                deviceId = attr(root, "DEV.device_id");
                state = State.AWAIT_STATUS;
                out.add(ack("AA", ctl, null));
            }
            case "DST.R01" -> {
                if (state != State.AWAIT_STATUS && state != State.READY) return reject(out, ctl, type);
                String qty = attr(root, "DST.new_observations_qty");
                announcedObservations = qty.matches("\\d{1,6}") ? Integer.parseInt(qty) : -1;
                state = State.READY;
                out.add(ack("AA", ctl, null));
                if (announcedObservations > 0) out.add(request("ROBS"));
            }
            case "OBS.R01" -> {
                if (state != State.READY && state != State.IN_TOPIC) return reject(out, ctl, type);
                if (observations.size() >= MAX_OBSERVATIONS) return reject(out, ctl, type);
                observations.add(xml.strip());
                state = State.IN_TOPIC;
                out.add(ack("AA", ctl, null));
            }
            case "EOT.R01" -> {
                if (state != State.IN_TOPIC && state != State.READY) return reject(out, ctl, type);
                out.add(ack("AA", ctl, null));
                out.add(end());
                state = State.TERMINATED;
            }
            case "END.R01" -> {
                out.add(ack("AA", ctl, null));
                state = State.TERMINATED;
            }
            default -> {
                errors.add("unsupported " + type);
                out.add(ack("AE", ctl, "message type " + type + " not supported"));
            }
        }
        return out;
    }

    private List<String> reject(List<String> out, String ctl, String type) {
        seenControlIds.remove(ctl);
        errors.add(type + " not allowed in state " + state);
        out.add(ack("AE", ctl, type + " not allowed in state " + state));
        return out;
    }

    private static String attr(Element root, String tag) {
        NodeList l = root.getElementsByTagName(tag);
        if (l.getLength() == 0) return "";
        Node v = ((Element) l.item(0)).getAttributeNode("V");
        return v == null ? "" : v.getNodeValue().trim();
    }

    private String header(String type) {
        String ts = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        return "<HDR><HDR.message_type V=\"" + type + "\"/><HDR.control_id V=\"" + (ownControlId++) + "\"/><HDR.version_id V=\"POCT1\"/><HDR.creation_dttm V=\"" + ts + "\"/></HDR>";
    }

    String ack(String code, String ackControlId, String note) {
        return "<ACK.R01>" + header("ACK.R01") + "<ACK><ACK.type_cd V=\"" + code + "\"/><ACK.ack_control_id V=\"" + esc(ackControlId) + "\"/>"
                + (note == null ? "" : "<ACK.note_txt V=\"" + esc(note) + "\"/>") + "</ACK></ACK.R01>";
    }

    private String request(String code) { return "<REQ.R01>" + header("REQ.R01") + "<REQ><REQ.request_cd V=\"" + code + "\"/></REQ></REQ.R01>"; }

    private String end() { return "<END.R01>" + header("END.R01") + "<TRM><TRM.reason_cd V=\"NRM\"/></TRM></END.R01>"; }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Builds a minimal device message (used by the simulator / tests and the gateway SDK path). */
    public static String deviceMessage(String type, String controlId, String body) {
        return "<" + type + "><HDR><HDR.message_type V=\"" + type + "\"/><HDR.control_id V=\"" + controlId + "\"/><HDR.version_id V=\"POCT1\"/>"
                + "<HDR.creation_dttm V=\"" + ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + "\"/></HDR>" + (body == null ? "" : body) + "</" + type + ">";
    }

    /** Extracts ACK.type_cd from a reply (AA / AE / AR), "" if none. */
    public static String ackCode(String reply) {
        int i = reply.indexOf("<ACK.type_cd V=\"");
        return i < 0 ? "" : reply.substring(i + 16, reply.indexOf('"', i + 16));
    }
}
