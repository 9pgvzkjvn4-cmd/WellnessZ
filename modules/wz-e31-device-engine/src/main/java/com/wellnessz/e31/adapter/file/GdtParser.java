package com.wellnessz.e31.adapter.file;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.wellnessz.e31.adapter.ParseContext;
import com.wellnessz.e31.adapter.ParsedRecord;
import com.wellnessz.e31.adapter.RecordAssembler;
import com.wellnessz.e31.adapter.RecordParser;
import com.wellnessz.e31.support.EngineException;

/**
 * {@code GDT_FIELDS}: German GDT device-data transfer (record type 6310 test data from spirometry / ECG vendor software).
 * Lines are {@code LLLFFFFcontent} (length, four-digit field id). Selectors: {@code 3000} (first occurrence),
 * {@code 8410=FEV1/8420} (field 8420 inside the test block whose 8410 is {@code FEV1}; the unit is 8421 of the same block),
 * {@code 6200+6201} (concatenation). Charset from field 9206 (1 = 7-bit, 2 = IBM CP437, 3 = ISO-8859-1) or the driver.
 */
public class GdtParser implements RecordParser {

    static final Pattern LINE = Pattern.compile("(\\d{3})(\\d{4})(.*)");
    static final Pattern BLOCK = Pattern.compile("(\\d{4})=([^/]+)/(\\d{4})");

    record Line(String field, String content) {
    }

    @Override
    public String parseType() {
        return "GDT_FIELDS";
    }

    @Override
    public List<ParsedRecord> parse(ParseContext ctx) {
        Charset cs = ctx.charset(StandardCharsets.ISO_8859_1);
        String probe = new String(ctx.body(), StandardCharsets.ISO_8859_1);
        Matcher charset = Pattern.compile("(?m)^\\d{3}9206(\\d)").matcher(probe);
        if (charset.find() && "2".equals(charset.group(1))) {
            cs = Charset.forName("IBM437");
        }
        List<Line> lines = lines(new String(ctx.body(), cs));
        if (lines.stream().noneMatch(l -> "8000".equals(l.field()))) {
            throw EngineException.parse("GDT record type (8000) missing");
        }
        RecordAssembler.SelectorSource src = new RecordAssembler.SelectorSource() {
            @Override
            public String value(String selector) {
                return GdtParser.value(lines, selector);
            }

            @Override
            public String unit(String selector) {
                Matcher m = BLOCK.matcher(selector);
                if (!m.matches() || !"8420".equals(m.group(3))) {
                    return null;
                }
                return block(lines, m.group(1), m.group(2), "8421");
            }
        };
        return List.of(RecordAssembler.assemble(0, ctx.pkg(), src, ctx.zone(), ctx.receivedAt()));
    }

    static List<Line> lines(String text) {
        List<Line> out = new ArrayList<>();
        int n = 0;
        for (String raw : text.split("\r\n|\n|\r")) {
            n++;
            if (raw.isEmpty()) {
                continue;
            }
            Matcher m = LINE.matcher(raw);
            if (!m.matches()) {
                throw EngineException.parse("GDT line " + n + " is malformed");
            }
            out.add(new Line(m.group(2), m.group(3)));
        }
        if (out.isEmpty()) {
            throw EngineException.parse("Empty GDT file");
        }
        return out;
    }

    static String value(List<Line> lines, String selector) {
        String s = selector.strip();
        if (s.contains("+")) {
            StringBuilder sb = new StringBuilder();
            for (String part : s.split("\\+")) {
                String v = value(lines, part);
                if (v == null) {
                    return null;
                }
                sb.append(v);
            }
            return sb.toString();
        }
        Matcher m = BLOCK.matcher(s);
        if (m.matches()) {
            return block(lines, m.group(1), m.group(2), m.group(3));
        }
        if (!s.matches("\\d{4}")) {
            throw new EngineException("parse_failed", "Invalid GDT selector in the driver package", true);
        }
        return lines.stream().filter(l -> l.field().equals(s)).map(Line::content).findFirst().orElse(null);
    }

    /** Field {@code wanted} inside the block opened by {@code blockField = blockValue}, up to the next {@code blockField}. */
    static String block(List<Line> lines, String blockField, String blockValue, String wanted) {
        boolean inside = false;
        for (Line l : lines) {
            if (l.field().equals(blockField)) {
                if (inside) {
                    return null;
                }
                inside = l.content().strip().equals(blockValue);
                continue;
            }
            if (inside && l.field().equals(wanted)) {
                return l.content();
            }
        }
        return null;
    }
}
