package com.wellnessz.e31.support;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Device time stamps as declared in the driver package ({@code parse.identity.effectiveTime.format}): Java date-time
 * patterns, {@code ISO_OFFSET}, {@code ISO}, {@code HL7_DTM} (and HL7 patterns with optional offset / precision) and
 * {@code EPOCH_MILLIS}. Times without an offset are interpreted in the declared or instance time zone.
 */
public final class TimeParsing {

    private static final Pattern HL7_DTM = Pattern.compile("(\\d{4})(\\d{2})?(\\d{2})?(\\d{2})?(\\d{2})?(\\d{2})?(?:\\.\\d{1,4})?([+-]\\d{4})?");

    private TimeParsing() {
    }

    public static Instant parse(String value, String format, ZoneId zone) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip();
        String f = format == null || format.isBlank() ? "ISO" : format;
        ZoneId z = zone == null ? ZoneOffset.UTC : zone;
        try {
            return switch (f.toUpperCase(Locale.ROOT)) {
                case "ISO", "ISO_OFFSET", "GATT_DATETIME" -> iso(v, z);
                case "EPOCH_MILLIS" -> Instant.ofEpochMilli(Long.parseLong(v));
                case "HL7_DTM" -> hl7(v, z);
                default -> pattern(v, f, z);
            };
        } catch (DateTimeParseException | NumberFormatException e) {
            if (f.startsWith("yyyyMMdd")) {
                Instant h = hl7(v, z);
                if (h != null) {
                    return h;
                }
            }
            throw EngineException.parse("Time stamp does not match the declared format " + f);
        }
    }

    public static Instant iso(String v, ZoneId z) {
        try {
            return OffsetDateTime.parse(v).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(v);
            } catch (DateTimeParseException e2) {
                if (v.length() == 10) {
                    return LocalDate.parse(v).atStartOfDay(z).toInstant();
                }
                return LocalDateTime.parse(v).atZone(z).toInstant();
            }
        }
    }

    /** HL7 v2 DTM: YYYY[MM[DD[HH[MM[SS[.S[S[S[S]]]]]]]]][+/-ZZZZ]. */
    public static Instant hl7(String v, ZoneId z) {
        Matcher m = HL7_DTM.matcher(v);
        if (!m.matches()) {
            return null;
        }
        int year = Integer.parseInt(m.group(1));
        int month = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
        int day = m.group(3) == null ? 1 : Integer.parseInt(m.group(3));
        int hour = m.group(4) == null ? 0 : Integer.parseInt(m.group(4));
        int minute = m.group(5) == null ? 0 : Integer.parseInt(m.group(5));
        int second = m.group(6) == null ? 0 : Integer.parseInt(m.group(6));
        LocalDateTime ldt = LocalDateTime.of(year, month, day, hour, minute, second);
        if (m.group(7) != null) {
            return ldt.atOffset(ZoneOffset.of(m.group(7).substring(0, 3) + ":" + m.group(7).substring(3))).toInstant();
        }
        return ldt.atZone(z).toInstant();
    }

    static Instant pattern(String v, String f, ZoneId z) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern(f, Locale.ROOT);
        if (f.contains("X") || f.contains("Z") || f.contains("x") || f.contains("O")) {
            return OffsetDateTime.parse(v, fmt).toInstant();
        }
        if (!f.contains("H") && !f.contains("h")) {
            return LocalDate.parse(v, fmt).atStartOfDay(z).toInstant();
        }
        return LocalDateTime.parse(v, fmt).atZone(z).toInstant();
    }

    /** Dates of birth and similar: {@code yyyyMMdd}, {@code ddMMyyyy}, ISO. */
    public static LocalDate date(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        String s = v.strip();
        try {
            if (s.matches("\\d{4}-\\d{2}-\\d{2}")) {
                return LocalDate.parse(s);
            }
            if (s.matches("\\d{8}")) {
                int first = Integer.parseInt(s.substring(0, 4));
                return first > 1900 && first < 2200 ? LocalDate.parse(s, DateTimeFormatter.BASIC_ISO_DATE)
                        : LocalDate.parse(s, DateTimeFormatter.ofPattern("ddMMyyyy", Locale.ROOT));
            }
        } catch (DateTimeParseException e) {
            return null;
        }
        return null;
    }

    public static ZoneId zone(String id) {
        if (id == null || id.isBlank()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(id);
        } catch (RuntimeException e) {
            return ZoneOffset.UTC;
        }
    }
}
