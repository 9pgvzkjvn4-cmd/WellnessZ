package com.wellnessz.ehsintake.mailbox;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.wellnessz.ehsintake.model.IncidentRecord;

/**
 * Parses notification e-mails whose body contains {@code Key: Value} lines, the common format of EHS system
 * alert mails (e.g. "Incident ID: INC-1234"). Returns empty when the mandatory incident id is missing.
 */
public final class MailBodyParser {

    private MailBodyParser() {
    }

    public static Optional<IncidentRecord> parse(String body, String sourceSystem, ZoneId zone) {
        Map<String, String> kv = new HashMap<>();
        for (String line : body.split("\\R")) {
            int idx = line.indexOf(':');
            if (idx > 0 && idx < 60) {
                kv.putIfAbsent(line.substring(0, idx).trim().toLowerCase(Locale.ROOT), line.substring(idx + 1).trim());
            }
        }
        String ref = first(kv, "incident id", "incident ref", "incident number", "reference");
        if (ref == null || !ref.matches("[A-Za-z0-9._/\\-]{1,100}")) {
            return Optional.empty();
        }
        String employeeId = first(kv, "employee id", "staff id", "employee number");
        if (employeeId != null && !employeeId.matches("[A-Za-z0-9\\-]{1,64}")) {
            employeeId = null;
        }
        String severity = first(kv, "severity", "risk rating");
        String injury = first(kv, "injury", "injured");
        return Optional.of(new IncidentRecord(sourceSystem, ref, date(first(kv, "date", "incident date", "occurred at"), zone),
                cap(first(kv, "location", "site"), 200), employeeId, cap(first(kv, "employee name", "name"), 200),
                cap(first(kv, "department", "org unit"), 64), cap(first(kv, "type", "incident type", "category"), 100),
                severity == null ? null : cap(severity.toUpperCase(Locale.ROOT), 20),
                injury != null && Set.of("y", "yes", "true", "1").contains(injury.toLowerCase(Locale.ROOT)),
                cap(first(kv, "description", "details", "summary"), 4000)));
    }

    private static String first(Map<String, String> kv, String... keys) {
        for (String k : keys) {
            String v = kv.get(k);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String cap(String v, int max) {
        return v == null || v.length() <= max ? v : v.substring(0, max);
    }

    private static Instant date(String s, ZoneId zone) {
        if (s == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(s.replace(' ', 'T')).atZone(zone).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
