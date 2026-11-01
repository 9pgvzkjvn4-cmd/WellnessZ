package io.intimed.engine.sim;

import io.intimed.engine.driver.DriverPackage;
import io.intimed.engine.parse.GattParser;
import io.intimed.engine.parse.SerialFrameParser;
import io.intimed.common.Hashing;
import io.intimed.common.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Family simulators (IM-E01-F10 / IM-E01-BL-026): render the driver's golden sessions into real protocol payloads
 * (HL7 ER7, ASTM, POCT1-A XML, GDT with computed line lengths, BLE characteristic values with IEEE-11073 SFLOAT,
 * serial frames with checksums, DICOM JSON, FHIR, vendor JSON) and derive negative cases (duplicate, malformed,
 * time-skew, out-of-range, wrong subject). Deterministic for a given seed so evidence is reproducible.
 */
public final class Simulator {
    private static final Pattern TOKEN = Pattern.compile("\\{\\{(SUBJECT|SERIAL|TS|TSISO|CTRL|CS|V:[A-Za-z0-9_]+)}}");

    private Simulator() { }

    public static Map<String, String> values(DriverPackage d) {
        Map<String, String> v = new LinkedHashMap<>();
        d.tests().path("values").properties().forEach(e -> v.put(e.getKey(), e.getValue().asString()));
        return v;
    }

    /** Values with a small deterministic variation (continuous feeds) – ±2 % of numeric values. */
    public static Map<String, String> vary(Map<String, String> base, long seed) {
        Map<String, String> v = new LinkedHashMap<>();
        java.util.Random r = new java.util.Random(seed);
        base.forEach((k, s) -> {
            try {
                java.math.BigDecimal b = new java.math.BigDecimal(s);
                double f = 1 + (r.nextDouble() - 0.5) * 0.04;
                java.math.BigDecimal n = b.multiply(java.math.BigDecimal.valueOf(f)).setScale(Math.max(0, b.scale()), java.math.RoundingMode.HALF_UP);
                v.put(k, n.toPlainString());
            } catch (NumberFormatException e) {
                v.put(k, s);
            }
        });
        return v;
    }

    public static String render(DriverPackage d, Map<String, String> values, String subject, Instant ts, String serial) {
        JsonNode g = d.tests().path("golden").get(0);
        String fmt = d.tests().path("timeFormat").asString("ISO");
        String tsFmt = "ISO".equals(fmt) ? ts.toString() : DateTimeFormatter.ofPattern(fmt).withZone(ZoneOffset.UTC).format(ts);
        String ctrl = Hashing.sha256(d.ref() + ts + subject + values).substring(0, 12).toUpperCase();
        if (g.has("gatt")) return gatt(g.get("gatt").asString(), values, serial, ts);
        if (g.has("gdtLines")) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode l : g.get("gdtLines")) {
                String content = subst(l.get(1).asString(), values, subject, tsFmt, ts.toString(), serial, ctrl);
                String line = l.get(0).asString() + content;
                sb.append(String.format("%03d", line.length() + 5)).append(line).append("\r\n");
            }
            return sb.toString();
        }
        String p = subst(g.get("payload").asString(), values, subject, tsFmt, ts.toString(), serial, ctrl);
        if (p.contains("{{CS}}")) {
            int star = p.indexOf("*{{CS}}");
            String body = p.substring(p.indexOf('$') + 1, star);
            p = p.replace("{{CS}}", SerialFrameParser.xorChecksum(body));
        }
        return p;
    }

    private static String subst(String tpl, Map<String, String> values, String subject, String ts, String tsIso, String serial, String ctrl) {
        Matcher m = TOKEN.matcher(tpl);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String t = m.group(1);
            String rep = switch (t) {
                case "SUBJECT" -> subject == null ? "" : subject;
                case "SERIAL" -> serial;
                case "TS" -> ts;
                case "TSISO" -> tsIso;
                case "CTRL" -> ctrl;
                case "CS" -> "{{CS}}";
                default -> values.getOrDefault(t.substring(2), "");
            };
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static String gatt(String ch, Map<String, String> v, String serial, Instant ts) {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        switch (ch) {
            case "2A35" -> {
                b.write(0x04); // mmHg, no timestamp, pulse present
                b.writeBytes(GattParser.encodeSfloat(Double.parseDouble(v.get("sys")), 0));
                b.writeBytes(GattParser.encodeSfloat(Double.parseDouble(v.get("dia")), 0));
                b.writeBytes(GattParser.encodeSfloat(Math.round((Double.parseDouble(v.get("sys")) + 2 * Double.parseDouble(v.get("dia"))) / 3), 0));
                b.writeBytes(GattParser.encodeSfloat(Double.parseDouble(v.get("pulse")), 0));
            }
            case "2A5E" -> {
                b.write(0x00);
                b.writeBytes(GattParser.encodeSfloat(Double.parseDouble(v.get("spo2")), 0));
                b.writeBytes(GattParser.encodeSfloat(Double.parseDouble(v.get("pulse")), 0));
            }
            case "2A1C" -> { b.write(0x00); b.writeBytes(GattParser.encodeFloat32(Double.parseDouble(v.get("temperature")), -1)); }
            default -> throw new IllegalArgumentException("no simulator for characteristic " + ch);
        }
        ObjectNode env = Json.obj().put("characteristic", ch).put("hex", HexFormat.of().formatHex(b.toByteArray()).toUpperCase())
                .put("serial", serial).put("address", "C0:" + Hashing.sha256(serial).substring(0, 10).replaceAll("(..)(?!$)", "$1:").toUpperCase()).put("time", ts.toString());
        return Json.write(env);
    }

    /** Negative case derivation for XT-11. */
    public static String negative(String kind, DriverPackage d, String nominal, Map<String, String> values, String subject, Instant ts, String serial) {
        return switch (kind) {
            case "MALFORMED" -> nominal.substring(0, Math.max(1, nominal.length() / 3)).replace("|", "#").replace("{", "[").replace("<", "(");
            case "TIME_SKEW" -> render(d, values, subject, ts.plusSeconds(7200), serial);
            case "OUT_OF_RANGE" -> {
                JsonNode o = d.tests().path("outOfRange");
                Map<String, String> v = new LinkedHashMap<>(values);
                v.put(o.get("key").asString(), o.get("value").asString());
                yield render(d, v, subject, ts.plusSeconds(1), serial);
            }
            case "WRONG_SUBJECT" -> render(d, values, "UNKNOWN-" + subject, ts.plusSeconds(2), serial);
            default -> nominal;
        };
    }
}
