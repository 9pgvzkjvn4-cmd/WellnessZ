package com.wellnessz.e31.adapter.hl7;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.wellnessz.e31.support.EngineException;

/**
 * Light segment / field model of an ER7 message used to evaluate the driver selectors ({@code PID-3.1}, {@code OBR-7},
 * {@code OBX[OBX-3.1=150456^MDC_PULS_OXIM_SAT_O2]/OBX-5}). Structure validation is done by HAPI ({@link Hl7Support}).
 */
public final class Hl7Segments {

    private static final Pattern SEGMENT = Pattern.compile("[A-Z][A-Z0-9]{2}");
    private static final Pattern NUMBER = Pattern.compile("\\d{1,3}");

    /** MDC unit codes (IEEE 11073-10101 dimensions) used in OBX-6 by PCD-01 senders → UCUM. */
    static final Map<String, String> MDC_UNITS = Map.ofEntries(
            Map.entry("MDC_DIM_MMHG", "mm[Hg]"), Map.entry("MDC_DIM_KILO_PASCAL", "kPa"), Map.entry("MDC_DIM_BEAT_PER_MIN", "/min"),
            Map.entry("MDC_DIM_RESP_PER_MIN", "/min"), Map.entry("MDC_DIM_PERCENT", "%"), Map.entry("MDC_DIM_DEGC", "Cel"),
            Map.entry("MDC_DIM_FAHR", "[degF]"), Map.entry("MDC_DIM_KILO_G", "kg"), Map.entry("MDC_DIM_LB", "[lb_av]"),
            Map.entry("MDC_DIM_CENTI_M", "cm"), Map.entry("MDC_DIM_INCH", "[in_i]"), Map.entry("MDC_DIM_L", "L"),
            Map.entry("MDC_DIM_L_PER_SEC", "L/s"), Map.entry("MDC_DIM_MILLI_SEC", "ms"), Map.entry("MDC_DIM_KG_PER_M_SQ", "kg/m2"),
            Map.entry("MDC_DIM_MILLI_G_PER_DL", "mg/dL"), Map.entry("MDC_DIM_MILLI_MOLE_PER_L", "mmol/L"), Map.entry("MDC_DIM_DIMLESS", "1"));

    private final List<String[]> segments = new ArrayList<>();
    private final char component;
    private final char repetition;
    private final char subcomponent;

    public Hl7Segments(String er7) {
        String normalised = er7.replace("\r\n", "\r").replace('\n', '\r');
        int start = normalised.indexOf("MSH");
        if (start < 0 || normalised.length() < start + 8) {
            throw EngineException.parse("Not an HL7 v2 message (no MSH segment)");
        }
        normalised = normalised.substring(start);
        char field = normalised.charAt(3);
        if (field != '|') {
            throw EngineException.parse("Unsupported HL7 field separator");
        }
        this.component = normalised.charAt(4);
        this.repetition = normalised.charAt(5);
        this.subcomponent = normalised.length() > 7 ? normalised.charAt(7) : '&';
        for (String seg : normalised.split("\r")) {
            if (seg.length() >= 3) {
                segments.add(seg.split("\\|", -1));
            }
        }
    }

    public List<String> segmentNames() {
        return segments.stream().map(s -> s[0]).toList();
    }

    public boolean has(String segment) {
        return segments.stream().anyMatch(s -> s[0].equals(segment));
    }

    public int count(String segment) {
        return (int) segments.stream().filter(s -> s[0].equals(segment)).count();
    }

    /** Field {@code n} of a segment row (HL7 numbering: MSH-1 is the field separator). */
    String field(String[] seg, int n) {
        if ("MSH".equals(seg[0])) {
            if (n == 1) {
                return "|";
            }
            int idx = n - 1;
            return idx < seg.length ? seg[idx] : null;
        }
        return n < seg.length ? seg[n] : null;
    }

    String component(String fieldValue, Integer comp, Integer sub) {
        if (fieldValue == null) {
            return null;
        }
        String rep = fieldValue.split(Pattern.quote(String.valueOf(repetition)), -1)[0];
        if (comp == null) {
            return unescape(rep);
        }
        String[] comps = rep.split(Pattern.quote(String.valueOf(component)), -1);
        if (comp < 1 || comp > comps.length) {
            return null;
        }
        String c = comps[comp - 1];
        if (sub == null) {
            return unescape(c);
        }
        String[] subs = c.split(Pattern.quote(String.valueOf(subcomponent)), -1);
        return sub >= 1 && sub <= subs.length ? unescape(subs[sub - 1]) : null;
    }

    static String unescape(String v) {
        if (v == null || v.indexOf('\\') < 0) {
            return v;
        }
        return v.replace("\\F\\", "|").replace("\\S\\", "^").replace("\\T\\", "&").replace("\\R\\", "~").replace("\\E\\", "\\");
    }

    /** First segment row matching the selector's optional filter. */
    String[] row(String segment, String filterSeg, Integer filterField, Integer filterComp, String filterValue) {
        for (String[] s : segments) {
            if (!s[0].equals(segment)) {
                continue;
            }
            if (filterSeg == null) {
                return s;
            }
            String actual = component(field(s, filterField), filterComp, null);
            String expected = filterValue.split(Pattern.quote(String.valueOf(component)), -1)[0];
            if (actual != null && (actual.equals(expected) || actual.equals(filterValue))) {
                return s;
            }
        }
        return null;
    }

    /** Value of a selector, {@code null} when the segment or field is absent. */
    public String value(String selector) {
        Selector m = matcher(selector);
        String[] row = row(m.group(1), m.group(2), m.group(3) == null ? null : Integer.valueOf(m.group(3)),
                m.group(4) == null ? null : Integer.valueOf(m.group(4)), m.group(5));
        if (row == null) {
            return null;
        }
        String v = component(field(row, Integer.parseInt(m.group(6))), m.group(7) == null ? null : Integer.valueOf(m.group(7)),
                m.group(8) == null ? null : Integer.valueOf(m.group(8)));
        if (v != null && m.group(7) == null && v.indexOf(component) >= 0) {
            v = v.substring(0, v.indexOf(component));
        }
        return v == null || v.isEmpty() ? null : v;
    }

    /** Unit of an OBX value selector (OBX-6 of the same row: MDC dimension code mapped to UCUM, else OBX-6.1 as UCUM). */
    public String unit(String selector) {
        Selector m = matcher(selector);
        if (!"OBX".equals(m.group(1)) || !"5".equals(m.group(6))) {
            return null;
        }
        String[] row = row("OBX", m.group(2), m.group(3) == null ? null : Integer.valueOf(m.group(3)),
                m.group(4) == null ? null : Integer.valueOf(m.group(4)), m.group(5));
        if (row == null) {
            return null;
        }
        String units = field(row, 6);
        if (units == null || units.isBlank()) {
            return null;
        }
        String c1 = component(units, 1, null);
        String c2 = component(units, 2, null);
        for (String c : new String[] {c2, c1}) {
            if (c != null && MDC_UNITS.containsKey(c.toUpperCase(Locale.ROOT))) {
                return MDC_UNITS.get(c.toUpperCase(Locale.ROOT));
            }
        }
        return c1 != null && !c1.matches("\\d+") ? c1 : null;
    }

    /**
     * Parsed selector {@code SEG[-COND]/SEG-f(.c(.s))}; {@link #group(int)} numbers the parts 1–8: segment, condition segment,
     * condition field, condition component, condition value, field, component, subcomponent (null when absent). Parsed by
     * hand (no backtracking regular expression over driver input).
     */
    record Selector(String[] parts) {
        String group(int i) {
            return parts[i];
        }
    }

    static Selector matcher(String selector) {
        String s = selector == null ? "" : selector.strip();
        String[] g = new String[9];
        int bracket = s.indexOf('[');
        String tail = s;
        if (bracket >= 0) {
            int close = s.indexOf("]/", bracket);
            if (bracket != 3 || close < 0) {
                throw invalid();
            }
            String cond = s.substring(bracket + 1, close);
            int eq = cond.indexOf('=');
            if (eq < 0) {
                throw invalid();
            }
            String[] key = path(cond.substring(0, eq), 2);
            g[2] = key[0];
            g[3] = key[1];
            g[4] = key[2];
            g[5] = cond.substring(eq + 1);
            tail = s.substring(close + 2);
            if (!tail.startsWith(s.substring(0, 3) + "-")) {
                throw invalid();
            }
        }
        String[] main = path(tail, 3);
        if (bracket >= 0 && !s.startsWith(main[0])) {
            throw invalid();
        }
        g[1] = main[0];
        g[6] = main[1];
        g[7] = main[2];
        g[8] = main[3];
        return new Selector(g);
    }

    /** {@code SEG-n(.n){0,maxDots-1}} → segment and numbers (null when absent). */
    private static String[] path(String p, int numbers) {
        int dash = p.indexOf('-');
        if (dash != 3 || !SEGMENT.matcher(p.substring(0, 3)).matches()) {
            throw invalid();
        }
        String[] nums = p.substring(4).split("\\.", -1);
        if (nums.length > numbers) {
            throw invalid();
        }
        String[] out = new String[numbers + 1];
        out[0] = p.substring(0, 3);
        for (int i = 0; i < nums.length; i++) {
            if (!NUMBER.matcher(nums[i]).matches() || i > 0 && nums[i].length() > 2) {
                throw invalid();
            }
            out[i + 1] = nums[i];
        }
        return out;
    }

    private static EngineException invalid() {
        return new EngineException("parse_failed", "Invalid HL7 selector in the driver package", true);
    }
}
