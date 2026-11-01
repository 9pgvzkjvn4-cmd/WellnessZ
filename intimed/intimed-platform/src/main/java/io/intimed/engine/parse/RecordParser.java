package io.intimed.engine.parse;

import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * HL7 v2 (P4 ORU^R01 PCD-01/02/03, IDCO; P5 data-manager bridges) and ASTM E1394 / LIS2-A2 (P5 analysers / middleware).
 * Configured by selectors in the driver grammar; MSH-9 is checked against the allowed message types.
 */
public class RecordParser implements PayloadParser {
    private final boolean hl7;

    public RecordParser(boolean hl7) { this.hl7 = hl7; }

    @Override public String type() { return hl7 ? "HL7V2" : "ASTM"; }

    @Override
    public List<Parsed> parse(JsonNode g, String payload) {
        PayloadParser.guard(payload, MAX_PAYLOAD);
        DelimitedRecords r = new DelimitedRecords(payload, hl7);
        Parsed p = new Parsed();
        if (hl7) {
            String type = r.first("MSH-9.1") + "^" + r.first("MSH-9.2");
            p.extras.put("messageType", type);
            p.extras.put("controlId", r.first("MSH-10"));
            p.extras.put("sendingApplication", r.first("MSH-3.1"));
            p.extras.put("sendingFacility", r.first("MSH-4.1"));
            p.extras.put("version", r.first("MSH-12"));
            if (g.has("messageTypes")) {
                boolean ok = false;
                for (JsonNode t : g.get("messageTypes")) ok |= t.asString().equals(type);
                if (!ok) throw new ParseException("message type " + type + " not supported by driver");
            }
            if (!r.names().contains("OBX") && g.path("requireObx").asBoolean(true)) throw new ParseException("no OBX segment");
        } else {
            if (!r.names().contains("H")) throw new ParseException("ASTM message without header record");
            p.extras.put("messageType", "ASTM-E1394");
        }
        g.path("identity").properties().forEach(e -> {
            if (e.getKey().endsWith("Format")) return;
            String v = r.first(e.getValue().asString());
            p.id(e.getKey(), v);
        });
        for (JsonNode f : g.path("fields")) {
            List<String> vs = r.select(f.get("sel").asString());
            if (vs.isEmpty()) continue;
            String unit = f.has("unit") ? f.get("unit").asString() : null;
            if (f.has("unitSel")) {
                String u = r.first(f.get("unitSel").asString());
                if (u != null && !u.isBlank()) unit = u;
            }
            p.val(f.get("key").asString(), vs.get(vs.size() - 1), unit);
        }
        if (g.has("alarmSel")) {
            List<String> al = r.select(g.get("alarmSel").asString());
            if (!al.isEmpty()) p.extras.put("alarm", String.join(";", al));
        }
        if (p.values.isEmpty()) throw new ParseException("no mapped results in message");
        return Parsed.one(p);
    }
}
