package io.intimed.engine.parse;

import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P1 serial / USB frames: the driver supplies a frame regex with named groups (keys and identity) and an optional
 * checksum rule ({@code xor-hex} NMEA-style between '$' and '*', or {@code sum8-hex}). Input is length-limited before the
 * regex runs (ReDoS guard for signed but third-party grammars).
 */
public class SerialFrameParser implements PayloadParser {
    @Override public String type() { return "SERIAL"; }

    @Override
    public List<Parsed> parse(JsonNode g, String payload) {
        PayloadParser.guard(payload, 8192);
        String frame = payload.strip();
        String term = g.path("terminator").asString("");
        if (!term.isEmpty()) frame = frame.replace(term, "");
        if (g.has("checksum")) verifyChecksum(g.get("checksum").asString(), frame);
        Matcher m = Pattern.compile(g.get("frame").asString()).matcher(frame);
        if (!m.matches()) throw new ParseException("serial frame does not match driver grammar");
        Parsed p = new Parsed();
        g.path("identity").properties().forEach(e -> p.id(e.getKey(), group(m, e.getValue().asString())));
        for (JsonNode f : g.path("fields")) p.val(f.get("key").asString(), group(m, f.get("group").asString()), f.path("unit").asString(null));
        if (p.values.isEmpty()) throw new ParseException("no values in frame");
        return Parsed.one(p);
    }

    private static String group(Matcher m, String name) {
        try { return m.group(name); } catch (IllegalArgumentException e) { return null; }
    }

    static void verifyChecksum(String rule, String frame) {
        int star = frame.lastIndexOf('*');
        if (star < 0 || star + 3 > frame.length()) throw new ParseException("checksum missing");
        String body = frame.substring(frame.startsWith("$") ? 1 : 0, star);
        int expect = Integer.parseInt(frame.substring(star + 1, star + 3), 16);
        int cs = 0;
        for (char c : body.toCharArray()) cs = "sum8-hex".equals(rule) ? (cs + c) & 0xff : cs ^ c;
        if (cs != expect) throw new ParseException("checksum mismatch");
    }

    public static String xorChecksum(String body) {
        int cs = 0;
        for (char c : body.toCharArray()) cs ^= c;
        return String.format("%02X", cs);
    }
}
