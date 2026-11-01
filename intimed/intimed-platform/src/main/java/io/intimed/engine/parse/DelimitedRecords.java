package io.intimed.engine.parse;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Segment / record model shared by HL7 v2 (ER7) and ASTM E1394 / CLSI LIS2-A2. Selector grammar:
 * {@code PID-3.1}, {@code OBR-7}, {@code OBX[OBX-3.1=150456]/OBX-5}, {@code R[R-3.4=GLU]/R-4}; {@code *} prefix returns all
 * matches. Field numbering follows each standard (HL7: MSH-1 is the field separator; ASTM: record type is field 1).
 */
public final class DelimitedRecords {
    private static final Pattern SIMPLE = Pattern.compile("^([A-Z][A-Z0-9]{0,2})-(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?$");
    private static final Pattern FILTER = Pattern.compile("^([A-Z][A-Z0-9]{0,2})\\[([A-Z][A-Z0-9]{0,2})-(\\d+)(?:\\.(\\d+))?=([^\\]]*)]/([A-Z][A-Z0-9]{0,2})-(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?$");
    private final List<String[]> recs = new ArrayList<>();
    private final boolean hl7;
    private final char comp;
    private final char sub;

    public DelimitedRecords(String text, boolean hl7) {
        String t = text.replace("\r\n", "\r").replace('\n', '\r');
        this.hl7 = hl7;
        if (hl7) {
            int s = t.indexOf("MSH");
            if (s < 0 || t.length() < s + 8) throw new ParseException("not an HL7 v2 message (no MSH)");
            t = t.substring(s);
            if (t.charAt(3) != '|') throw new ParseException("unsupported HL7 field separator");
            comp = t.charAt(4);
            sub = t.length() > 7 ? t.charAt(7) : '&';
        } else {
            comp = '^';
            sub = '&';
        }
        for (String r : t.split("\r")) {
            String line = r.strip();
            // ASTM frames may carry frame number and checksum: <STX>1H|...<ETX>xx – strip control characters
            line = line.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
            if (!hl7 && line.length() > 1 && Character.isDigit(line.charAt(0)) && Character.isLetter(line.charAt(1))) line = line.substring(1);
            if (line.length() >= 1) recs.add(line.split("\\|", -1));
        }
        if (recs.isEmpty()) throw new ParseException("no records");
    }

    public List<String> names() { return recs.stream().map(r -> r[0]).toList(); }

    public List<String[]> records(String name) { return recs.stream().filter(r -> r[0].equals(name)).toList(); }

    public String field(String[] rec, int n) {
        int idx;
        if (hl7) idx = rec[0].equals("MSH") ? n - 1 : n;
        else idx = n - 1;
        if (rec[0].equals("MSH") && n == 1) return "|";
        return idx >= 0 && idx < rec.length ? rec[idx] : "";
    }

    public String part(String field, Integer c, Integer s) {
        if (c == null) return field;
        String[] cs = field.split(Pattern.quote(String.valueOf(comp)), -1);
        String v = c - 1 < cs.length ? cs[c - 1] : "";
        if (s == null) return v;
        String[] ss = v.split(Pattern.quote(String.valueOf(sub)), -1);
        return s - 1 < ss.length ? ss[s - 1] : "";
    }

    public String first(String selector) {
        List<String> all = select(selector);
        return all.isEmpty() ? null : all.get(0);
    }

    public List<String> select(String selector) {
        String sel = selector.trim();
        List<String> out = new ArrayList<>();
        Matcher m = SIMPLE.matcher(sel);
        if (m.matches()) {
            for (String[] r : records(m.group(1))) out.add(part(field(r, Integer.parseInt(m.group(2))), num(m.group(3)), num(m.group(4))));
            return out;
        }
        m = FILTER.matcher(sel);
        if (!m.matches()) throw new IllegalArgumentException("invalid selector " + selector);
        for (String[] r : records(m.group(1))) {
            String f = part(field(r, Integer.parseInt(m.group(3))), num(m.group(4)), null);
            if (!f.equals(m.group(5))) continue;
            out.add(part(field(r, Integer.parseInt(m.group(7))), num(m.group(8)), num(m.group(9))));
        }
        return out;
    }

    private static Integer num(String s) { return s == null ? null : Integer.parseInt(s); }

}
