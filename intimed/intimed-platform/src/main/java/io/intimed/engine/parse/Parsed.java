package io.intimed.engine.parse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Output of the parse stage: identity (subject identifier as sent by the device, device serial, effective time, badge,
 * session/order references) and raw values per driver key with the unit the device sent. One payload may carry several
 * sets (e.g. periodic PCD-01 with several time points, CSV with several rows) – each becomes one observation.
 */
public final class Parsed {
    public final Map<String, String> identity = new LinkedHashMap<>();
    public final Map<String, Raw> values = new LinkedHashMap<>();
    public final List<String> warnings = new ArrayList<>();
    public final Map<String, String> extras = new LinkedHashMap<>();

    public record Raw(String value, String unit) { }

    public Parsed id(String k, String v) { if (v != null && !v.isBlank()) identity.put(k, v.trim()); return this; }
    public Parsed val(String k, String v, String unit) { if (v != null && !v.isBlank()) values.put(k, new Raw(v.trim(), unit == null || unit.isBlank() ? null : unit.trim())); return this; }

    public static List<Parsed> one(Parsed p) { List<Parsed> l = new ArrayList<>(); l.add(p); return l; }
}
