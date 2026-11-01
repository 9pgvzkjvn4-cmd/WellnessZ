package com.wellnessz.e31.adapter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.wellnessz.e31.driver.DriverPackage;
import com.wellnessz.e31.support.EngineException;
import com.wellnessz.e31.support.TimeParsing;

/**
 * Turns selector look-ups into a typed {@link ParsedRecord}: field typing (QUANTITY, BOOLEAN, CODE, STRING), required
 * fields, and the identity block including the device time in the declared format and zone.
 */
public final class RecordAssembler {

    private RecordAssembler() {
    }

    /** Resolves selectors of one parse type against one record. */
    public interface SelectorSource {

        /** Raw text of a selector, {@code null} when absent. */
        String value(String selector);

        /** Unit reported by the device for the selector's value, {@code null} when the message carries none. */
        default String unit(String selector) {
            return null;
        }
    }

    public static ParsedRecord assemble(int index, DriverPackage pkg, SelectorSource src, ZoneId zone, Instant fallbackTime) {
        Map<String, ParsedRecord.Value> values = new LinkedHashMap<>();
        Map<String, String> technical = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        for (DriverPackage.FieldSpec f : pkg.parse().fields()) {
            String raw = f.selector() == null ? src.value(f.key()) : src.value(f.selector());
            if (raw == null || raw.isBlank()) {
                if (f.required()) {
                    throw EngineException.parse("Required field " + f.key() + " is missing");
                }
                continue;
            }
            if (f.technical()) {
                technical.put(f.key(), raw.strip());
                continue;
            }
            String reported = f.selector() == null ? null : src.unit(f.selector());
            values.put(f.key(), typed(f, raw.strip(), reported == null ? f.unit() : reported));
        }
        ParsedRecord.Identity identity = identity(pkg.parse().identity(), src, zone, fallbackTime, technical);
        return new ParsedRecord(index, values, identity, technical, notes);
    }

    public static ParsedRecord.Value typed(DriverPackage.FieldSpec f, String raw, String unit) {
        return switch (f.type()) {
            case "QUANTITY" -> new ParsedRecord.Value(f.key(), "QUANTITY", null, number(f.key(), raw), unit);
            case "BOOLEAN" -> new ParsedRecord.Value(f.key(), "BOOLEAN", bool(f.key(), raw), null, null);
            default -> new ParsedRecord.Value(f.key(), f.type(), raw, null, null);
        };
    }

    public static BigDecimal number(String key, String raw) {
        String s = raw.strip().replace(',', '.');
        if (s.startsWith("+")) {
            s = s.substring(1);
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw EngineException.parse("Field " + key + " is not numeric");
        }
    }

    static String bool(String key, String raw) {
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "true", "1", "y", "yes", "t", "pass", "passed" -> "true";
            case "false", "0", "n", "no", "f", "fail", "failed" -> "false";
            default -> throw EngineException.parse("Field " + key + " is not a boolean");
        };
    }

    static ParsedRecord.Identity identity(DriverPackage.IdentitySpec spec, SelectorSource src, ZoneId zone, Instant fallbackTime,
                                          Map<String, String> technical) {
        String employee = sel(spec.employee(), src, technical);
        String badge = sel(spec.badge(), src, technical);
        String serial = sel(spec.deviceSerial(), src, technical);
        String order = sel(spec.orderRef(), src, technical);
        Instant time = fallbackTime;
        if (spec.effectiveSelector() != null && !"RECEIVED_AT".equals(spec.effectiveSelector())) {
            String raw = sel(spec.effectiveSelector(), src, technical);
            if (raw != null) {
                ZoneId z = spec.timeZone() == null ? zone : TimeParsing.zone(spec.timeZone());
                time = TimeParsing.parse(raw, spec.effectiveFormat(), z);
            }
        }
        LocalDate dob = TimeParsing.date(sel(spec.dateOfBirth(), src, technical));
        String sex = sex(sel(spec.sex(), src, technical));
        Integer age = null;
        String ageRaw = sel(spec.ageYears(), src, technical);
        if (ageRaw != null) {
            try {
                age = new BigDecimal(ageRaw.strip()).intValue();
            } catch (NumberFormatException e) {
                throw EngineException.parse("Age is not numeric");
            }
        }
        BigDecimal height = null;
        String hRaw = sel(spec.heightCm(), src, technical);
        if (hRaw != null) {
            height = number("heightCm", hRaw);
        }
        return new ParsedRecord.Identity(blankToNull(employee), blankToNull(badge), blankToNull(serial), blankToNull(order), time,
                blankToNull(sel(spec.name(), src, technical)), dob, sex, age, height, blankToNull(sel(spec.operator(), src, technical)),
                null, null, null);
    }

    private static String sel(String selector, SelectorSource src, Map<String, String> technical) {
        if (selector == null) {
            return null;
        }
        if (technical.containsKey(selector)) {
            return technical.get(selector);
        }
        String v = src.value(selector);
        return v == null ? null : v.strip();
    }

    /** M | F from the common vendor encodings (M/F, 1/2 as in GDT 3110, male/female). */
    public static String sex(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw.strip().toUpperCase(Locale.ROOT)) {
            case "M", "1", "MALE", "MAN" -> "M";
            case "F", "W", "2", "FEMALE", "WOMAN" -> "F";
            default -> null;
        };
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
