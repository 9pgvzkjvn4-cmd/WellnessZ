package io.intimed.engine.parse;

import io.intimed.common.Json;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P3 Bluetooth LE standard GATT health profiles delivered by a gateway/tablet/kiosk as {@code {characteristic, hex,
 * serial, address}}: Blood Pressure Measurement 0x2A35 (BLP), PLX Spot-Check 0x2A5E (PLXP), Temperature Measurement
 * 0x2A1C (HTP), Weight Measurement 0x2A9D (WSS). IEEE 11073-20601 SFLOAT/FLOAT decoding with reserved values rejected.
 */
public class GattParser implements PayloadParser {
    @Override public String type() { return "BLE_GATT"; }

    @Override
    public List<Parsed> parse(JsonNode g, String payload) {
        PayloadParser.guard(payload, 16384);
        JsonNode env;
        try { env = Json.parse(payload); } catch (RuntimeException e) { throw new ParseException("BLE envelope is not JSON"); }
        String ch = env.path("characteristic").asString("").toUpperCase();
        if (g.has("characteristic") && !g.get("characteristic").asString().equalsIgnoreCase(ch)) throw new ParseException("unexpected characteristic " + ch);
        byte[] b;
        try { b = HexFormat.of().parseHex(env.path("hex").asString("").replace(" ", "")); } catch (IllegalArgumentException e) { throw new ParseException("invalid hex"); }
        Map<String, String[]> d = decode(ch, b);
        Parsed p = new Parsed();
        p.id("serial", env.path("serial").asString(null)).id("address", env.path("address").asString(null));
        if (d.containsKey("time")) p.id("time", d.get("time")[0]);
        else p.id("time", env.path("time").asString(null));
        for (JsonNode f : g.path("fields")) {
            String[] v = d.get(f.get("decoded").asString());
            if (v != null) p.val(f.get("key").asString(), v[0], v[1]);
        }
        if (p.values.isEmpty()) throw new ParseException("no values decoded");
        return Parsed.one(p);
    }

    public static Map<String, String[]> decode(String ch, byte[] b) {
        Map<String, String[]> out = new LinkedHashMap<>();
        if (b.length < 3) throw new ParseException("characteristic value too short");
        int flags = b[0] & 0xff;
        int i = 1;
        switch (ch) {
            case "2A35" -> {
                need(b, 7);
                String u = (flags & 1) == 1 ? "kPa" : "mm[Hg]";
                out.put("systolic", new String[]{sfloat(b, 1), u});
                out.put("diastolic", new String[]{sfloat(b, 3), u});
                out.put("map", new String[]{sfloat(b, 5), u});
                i = 7;
                if ((flags & 2) != 0) { need(b, i + 7); out.put("time", new String[]{time(b, i), null}); i += 7; }
                if ((flags & 4) != 0) { need(b, i + 2); out.put("pulse", new String[]{sfloat(b, i), "/min"}); i += 2; }
                if ((flags & 8) != 0) { need(b, i + 1); i += 1; }
                if ((flags & 16) != 0) { need(b, i + 2); int st = (b[i] & 0xff) | (b[i + 1] & 0xff) << 8; out.put("status", new String[]{String.valueOf(st), null}); }
            }
            case "2A5E" -> {
                need(b, 5);
                out.put("spo2", new String[]{sfloat(b, 1), "%"});
                out.put("pulse", new String[]{sfloat(b, 3), "/min"});
                i = 5;
                if ((flags & 1) != 0) { need(b, i + 7); out.put("time", new String[]{time(b, i), null}); i += 7; }
                if ((flags & 2) != 0) { need(b, i + 2); i += 2; }
                if ((flags & 4) != 0) { need(b, i + 3); i += 3; }
                if ((flags & 8) != 0) { need(b, i + 2); out.put("pi", new String[]{sfloat(b, i), "%"}); }
            }
            case "2A1C" -> {
                need(b, 5);
                out.put("temperature", new String[]{float32(b, 1), (flags & 1) == 1 ? "[degF]" : "Cel"});
                if ((flags & 2) != 0) { need(b, 12); out.put("time", new String[]{time(b, 5), null}); }
            }
            case "2A9D" -> {
                need(b, 3);
                int raw = (b[1] & 0xff) | (b[2] & 0xff) << 8;
                boolean imperial = (flags & 1) == 1;
                out.put("weight", new String[]{new BigDecimal(raw).multiply(new BigDecimal(imperial ? "0.01" : "0.005")).stripTrailingZeros().toPlainString(), imperial ? "[lb_av]" : "kg"});
                if ((flags & 2) != 0) { need(b, 10); out.put("time", new String[]{time(b, 3), null}); }
            }
            default -> throw new ParseException("unsupported GATT characteristic " + ch);
        }
        return out;
    }

    private static void need(byte[] b, int n) { if (b.length < n) throw new ParseException("truncated characteristic value"); }

    /** IEEE 11073 16-bit SFLOAT. */
    public static String sfloat(byte[] b, int o) {
        int raw = (b[o] & 0xff) | (b[o + 1] & 0xff) << 8;
        if (raw == 0x07FF || raw == 0x0800 || raw == 0x07FE || raw == 0x0802 || raw == 0x0801) throw new ParseException("SFLOAT special value (NaN/NRes/INF)");
        int mant = raw & 0x0FFF;
        if (mant >= 0x0800) mant -= 0x1000;
        int exp = raw >> 12;
        if (exp >= 8) exp -= 16;
        return new BigDecimal(mant).scaleByPowerOfTen(exp).stripTrailingZeros().toPlainString();
    }

    /** IEEE 11073 32-bit FLOAT. */
    public static String float32(byte[] b, int o) {
        int mant = (b[o] & 0xff) | (b[o + 1] & 0xff) << 8 | (b[o + 2] & 0xff) << 16;
        if (mant == 0x7FFFFF || mant == 0x800000 || mant == 0x7FFFFE || mant == 0x800002) throw new ParseException("FLOAT special value");
        if (mant >= 0x800000) mant -= 0x1000000;
        int exp = b[o + 3];
        return new BigDecimal(mant).scaleByPowerOfTen(exp).stripTrailingZeros().toPlainString();
    }

    private static String time(byte[] b, int o) {
        int y = (b[o] & 0xff) | (b[o + 1] & 0xff) << 8;
        return String.format("%04d-%02d-%02dT%02d:%02d:%02dZ", y, b[o + 2], b[o + 3], b[o + 4], b[o + 5], b[o + 6]);
    }

    /** Encoders used by the simulator. */
    public static byte[] encodeSfloat(double v, int exp) {
        int mant = (int) Math.round(v / Math.pow(10, exp));
        int raw = ((exp & 0xF) << 12) | (mant & 0x0FFF);
        return new byte[]{(byte) raw, (byte) (raw >> 8)};
    }

    public static byte[] encodeFloat32(double v, int exp) {
        int mant = (int) Math.round(v / Math.pow(10, exp));
        return new byte[]{(byte) mant, (byte) (mant >> 8), (byte) (mant >> 16), (byte) exp};
    }
}
