package com.wellnessz.e31.adapter.serial;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.wellnessz.e31.adapter.ParseContext;
import com.wellnessz.e31.adapter.ParsedRecord;
import com.wellnessz.e31.adapter.RecordAssembler;
import com.wellnessz.e31.adapter.RecordParser;
import com.wellnessz.e31.support.EngineException;
import com.wellnessz.e31.support.TimeParsing;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code REGEX_LINES}: RS-232 / USB-CDC frames from the Device Host agent ({@code /device-ingest/serial}); framing is done at
 * the edge with the driver's {@code protocol.framing}, parsing centrally. Each frame is one record; every field selector is
 * a regular expression applied per line with a named group {@code v} (or group 1). A frame matching none of the measured
 * fields is malformed.
 */
public class RegexLinesParser implements RecordParser {

    private static final Map<String, Pattern> CACHE = new ConcurrentHashMap<>();
    private final JsonMapper json;

    public RegexLinesParser(JsonMapper json) {
        this.json = json;
    }

    @Override
    public String parseType() {
        return "REGEX_LINES";
    }

    @Override
    public List<ParsedRecord> parse(ParseContext ctx) {
        JsonNode root;
        try {
            root = json.readTree(ctx.body());
        } catch (JacksonException e) {
            throw EngineException.parse("Serial envelope is not valid JSON");
        }
        JsonNode records = root.path("records");
        if (!records.isArray() || records.isEmpty() || records.size() > 100) {
            throw EngineException.parse("Serial envelope needs 1–100 records");
        }
        List<ParsedRecord> out = new ArrayList<>();
        for (JsonNode r : records) {
            String text = decode(r.path("encoding").asString("TEXT"), r.path("data").asString(""));
            Instant receivedAt = r.hasNonNull("receivedAt") ? TimeParsing.iso(r.get("receivedAt").asString(), ctx.zone()) : ctx.receivedAt();
            ParsedRecord p = parseFrame(out.size(), ctx, text, receivedAt);
            ParsedRecord.Identity id = p.identity().withEnvelope(r.hasNonNull("deviceInstanceId") ? r.get("deviceInstanceId").asString() : null,
                    r.hasNonNull("sessionId") ? r.get("sessionId").asString() : null, null,
                    r.hasNonNull("operator") ? r.get("operator").asString() : null, receivedAt);
            out.add(new ParsedRecord(p.index(), p.values(), id, p.technical(), p.notes()));
        }
        return out;
    }

    public static ParsedRecord parseFrame(int index, ParseContext ctx, String text, Instant receivedAt) {
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        RecordAssembler.SelectorSource src = selector -> {
            Pattern p = CACHE.computeIfAbsent(selector, RegexLinesParser::compile);
            for (String line : lines) {
                Matcher m = p.matcher(line.strip());
                if (m.find()) {
                    try {
                        return m.group("v");
                    } catch (IllegalArgumentException noNamedGroup) {
                        return m.groupCount() >= 1 ? m.group(1) : m.group();
                    }
                }
            }
            return null;
        };
        ParsedRecord rec = RecordAssembler.assemble(index, ctx.pkg(), src, ctx.zone(), receivedAt);
        if (rec.values().isEmpty()) {
            throw EngineException.parse("Serial frame matches none of the driver's fields");
        }
        return rec;
    }

    static Pattern compile(String regex) {
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new EngineException("parse_failed", "Invalid regular expression in the driver package", true);
        }
    }

    static String decode(String encoding, String data) {
        try {
            return switch (encoding) {
                case "HEX" -> new String(HexFormat.of().parseHex(data.replace(" ", "")), StandardCharsets.US_ASCII);
                case "BASE64" -> new String(Base64.getDecoder().decode(data), StandardCharsets.US_ASCII);
                default -> data;
            };
        } catch (IllegalArgumentException e) {
            throw EngineException.parse("Serial frame is not valid " + encoding);
        }
    }
}
