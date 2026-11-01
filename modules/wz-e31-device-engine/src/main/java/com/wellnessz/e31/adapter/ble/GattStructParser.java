package com.wellnessz.e31.adapter.ble;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.wellnessz.e31.adapter.ParseContext;
import com.wellnessz.e31.adapter.ParsedRecord;
import com.wellnessz.e31.adapter.RecordAssembler;
import com.wellnessz.e31.adapter.RecordParser;
import com.wellnessz.e31.driver.DriverPackage;
import com.wellnessz.e31.support.EngineException;
import com.wellnessz.e31.support.TimeParsing;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GATT_STRUCT}: Bluetooth SIG GATT health-profile characteristics posted by gateways, the nurse tablet and kiosks
 * ({@code /device-ingest/ble}, ≤ 100 records). Each record's value (HEX or BASE64) is decoded with the ordered struct of the
 * driver – e.g. Blood Pressure Measurement 0x2A35 (BLP), Temperature Measurement 0x2A1C (HTP), PLX Spot-check 0x2A5E (PLX),
 * Weight Measurement 0x2A9D (WSS), Body Composition Measurement 0x2A9C (BCS).
 *
 * <p>Field selector grammar: {@code TYPE[:option=value…]} with TYPE {@code UINT8 | UINT16 | UINT32 | SINT16 | SFLOAT | FLOAT |
 * DATETIME | FLAG} and options {@code flagBit=n} (present only when the flag bit is set), {@code bit=n} (FLAG),
 * {@code scale=x}, {@code unitFlagBit=n} + {@code altUnit=U} [+ {@code altScale=x}] (unit switch, e.g. kPa or imperial).
 * The field keyed {@code flags} supplies the flags (UINT8 or UINT16).</p>
 */
public class GattStructParser implements RecordParser {

    private final JsonMapper json;

    public GattStructParser(JsonMapper json) {
        this.json = json;
    }

    @Override
    public String parseType() {
        return "GATT_STRUCT";
    }

    record Selector(String type, Integer flagBit, Integer bit, BigDecimal scale, Integer unitFlagBit, String altUnit, BigDecimal altScale) {
    }

    static Selector selector(String s) {
        String[] parts = s.split(":");
        Map<String, String> opts = new HashMap<>();
        for (int i = 1; i < parts.length; i++) {
            String[] kv = parts[i].split("=", 2);
            opts.put(kv[0], kv.length > 1 ? kv[1] : "");
        }
        try {
            return new Selector(parts[0].toUpperCase(Locale.ROOT), int0(opts.get("flagBit")), int0(opts.get("bit")),
                    opts.containsKey("scale") ? new BigDecimal(opts.get("scale")) : null, int0(opts.get("unitFlagBit")), opts.get("altUnit"),
                    opts.containsKey("altScale") ? new BigDecimal(opts.get("altScale")) : null);
        } catch (NumberFormatException e) {
            throw new EngineException("parse_failed", "Invalid GATT selector in the driver package", true);
        }
    }

    private static Integer int0(String v) {
        return v == null ? null : Integer.valueOf(v);
    }

    @Override
    public List<ParsedRecord> parse(ParseContext ctx) {
        JsonNode root;
        try {
            root = json.readTree(ctx.body());
        } catch (JacksonException e) {
            throw EngineException.parse("BLE envelope is not valid JSON");
        }
        JsonNode records = root.path("records");
        if (!records.isArray() || records.isEmpty() || records.size() > 100) {
            throw EngineException.parse("BLE envelope needs 1–100 records");
        }
        List<ParsedRecord> out = new ArrayList<>();
        String expectedChar = ctx.pkg().protocolText("characteristic");
        for (JsonNode r : records) {
            String characteristic = r.path("characteristic").asString("");
            if (expectedChar != null && !expectedChar.equalsIgnoreCase(characteristic)) {
                throw EngineException.profile("Characteristic " + characteristic + " is not served by the driver");
            }
            byte[] value = decode(r.path("encoding").asString("HEX"), r.path("value").asString(""));
            Instant receivedAt = r.hasNonNull("receivedAt") ? TimeParsing.iso(r.get("receivedAt").asString(), ctx.zone()) : ctx.receivedAt();
            ParsedRecord p = decodeRecord(out.size(), ctx.pkg(), value, ctx, receivedAt);
            ParsedRecord.Identity id = p.identity().withEnvelope(text(r, "deviceInstanceId"), text(r, "sessionId"), text(r, "queueEntryId"),
                    text(r, "operator"), receivedAt);
            out.add(new ParsedRecord(p.index(), p.values(), id, p.technical(), p.notes()));
        }
        return out;
    }

    static String text(JsonNode n, String member) {
        JsonNode v = n.get(member);
        return v == null || v.isNull() ? null : v.asString();
    }

    static byte[] decode(String encoding, String value) {
        try {
            return "BASE64".equalsIgnoreCase(encoding) ? Base64.getDecoder().decode(value)
                    : HexFormat.of().parseHex(value.replace(" ", "").replace(":", ""));
        } catch (IllegalArgumentException e) {
            throw EngineException.parse("BLE value is not valid " + encoding);
        }
    }

    /** Decodes one characteristic value with the driver's ordered struct. */
    public static ParsedRecord decodeRecord(int index, DriverPackage pkg, byte[] value, ParseContext ctx, Instant receivedAt) {
        Map<String, String> raw = new LinkedHashMap<>();
        Map<String, String> units = new HashMap<>();
        int pos = 0;
        long flags = 0;
        for (DriverPackage.FieldSpec f : pkg.parse().fields()) {
            Selector s = selector(f.selector() == null ? "UINT8" : f.selector());
            if (s.flagBit() != null && (flags & (1L << s.flagBit())) == 0) {
                continue;
            }
            if ("FLAG".equals(s.type())) {
                raw.put(f.key(), (flags & (1L << (s.bit() == null ? 0 : s.bit()))) != 0 ? "true" : "false");
                continue;
            }
            int size = switch (s.type()) {
                case "UINT8" -> 1;
                case "UINT16", "SINT16", "SFLOAT" -> 2;
                case "UINT32", "FLOAT" -> 4;
                case "DATETIME" -> 7;
                default -> throw new EngineException("parse_failed", "Unknown GATT type " + s.type(), true);
            };
            if (pos + size > value.length) {
                throw EngineException.parse("BLE value too short for field " + f.key());
            }
            long u = 0;
            for (int i = size - 1; i >= 0 && !"DATETIME".equals(s.type()); i--) {
                u = (u << 8) | (value[pos + i] & 0xFF);
            }
            if ("flags".equals(f.key())) {
                flags = u;
                raw.put(f.key(), Long.toString(u));
                pos += size;
                continue;
            }
            boolean alt = s.unitFlagBit() != null && (flags & (1L << s.unitFlagBit())) != 0;
            BigDecimal scale = alt && s.altScale() != null ? s.altScale() : s.scale();
            BigDecimal number = switch (s.type()) {
                case "SFLOAT" -> Ieee11073.sfloat((int) u);
                case "FLOAT" -> Ieee11073.floatValue(u);
                case "SINT16" -> BigDecimal.valueOf((short) u);
                case "DATETIME" -> null;
                default -> BigDecimal.valueOf(u);
            };
            if ("DATETIME".equals(s.type())) {
                int year = (value[pos] & 0xFF) | ((value[pos + 1] & 0xFF) << 8);
                raw.put(f.key(), String.format(Locale.ROOT, "%04d-%02d-%02dT%02d:%02d:%02d", year, value[pos + 2] & 0xFF, value[pos + 3] & 0xFF,
                        value[pos + 4] & 0xFF, value[pos + 5] & 0xFF, value[pos + 6] & 0xFF));
            } else if (number != null) {
                if (scale != null) {
                    number = number.multiply(scale, MathContext.DECIMAL64).stripTrailingZeros();
                }
                raw.put(f.key(), number.toPlainString());
                if (alt && s.altUnit() != null) {
                    units.put(f.key(), s.altUnit());
                }
            }
            pos += size;
        }
        // contract-style unit flag: a FLAG field keyed unit<Unit> (e.g. unitKpa) switches the quantities whose mapping converts
        // from that unit (SFLOAT values of the Blood Pressure Measurement in kPa)
        for (DriverPackage.FieldSpec f : pkg.parse().fields()) {
            String sel = f.selector() == null ? "" : f.selector();
            if (sel.startsWith("FLAG") && f.key().startsWith("unit") && "true".equals(raw.get(f.key()))) {
                String unit = f.key().substring(4);
                for (DriverPackage.Mapping m : pkg.mappings()) {
                    if (m.convert() != null && m.convert().fromUnit().equalsIgnoreCase(unit) && raw.containsKey(m.key()) && !units.containsKey(m.key())) {
                        units.put(m.key(), m.convert().fromUnit());
                    }
                }
            }
        }
        RecordAssembler.SelectorSource src = new RecordAssembler.SelectorSource() {
            @Override
            public String value(String selector) {
                return raw.get(selector);
            }

            @Override
            public String unit(String selector) {
                return units.get(selector);
            }
        };
        DriverPackage keyed = keyedSelectors(pkg);
        return RecordAssembler.assemble(index, keyed, src, ctx.zone(), receivedAt);
    }

    /** The assembler resolves by key after decoding: rebuild the field list with selector = key. */
    static DriverPackage keyedSelectors(DriverPackage pkg) {
        List<DriverPackage.FieldSpec> fields = pkg.parse().fields().stream()
                .map(f -> new DriverPackage.FieldSpec(f.key(), f.key(), f.type(), f.unit(), f.required(), f.technical())).toList();
        DriverPackage.ParseSpec ps = new DriverPackage.ParseSpec(pkg.parse().type(), pkg.parse().identity(), pkg.parse().records(),
                pkg.parse().namespaces(), fields);
        return new DriverPackage(pkg.packageId(), pkg.version(), pkg.family(), pkg.models(), pkg.adapter(), pkg.options(),
                pkg.engineCompatibility(), pkg.capabilities(), pkg.protocol(), ps, pkg.mappings(), pkg.referenceRanges(), pkg.predicted(),
                pkg.qualityGates(), pkg.bindings(), pkg.testVectors(), pkg.signature(), pkg.canonicalJson(), pkg.sha256());
    }
}
