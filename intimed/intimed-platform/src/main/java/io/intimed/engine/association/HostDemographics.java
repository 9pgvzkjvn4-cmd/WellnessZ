package io.intimed.engine.association;

import io.intimed.common.Json;
import io.intimed.store.DocumentStore;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Host demographics (IM-E11-BL-006): the subject registry becomes a cache of the host's patient context. Birth date
 * (HL7 PID-7 / FHIR Patient.birthDate) and administrative sex (PID-8 M/F/U/O/A/N / Patient.gender) are taken from ADT
 * A01/A04/A08 over MLLP or from a FHIR Patient create/update, with provenance per field ({@code source} HL7_ADT | FHIR |
 * MANUAL, {@code at}, message control id). A manually entered value keeps precedence only while it is newer than the host
 * message; older manual values are replaced. Only partitioning factors are stored – the router uses them exactly like a
 * registry entry (age band and sex partition of reference intervals).
 */
@Service
public class HostDemographics {
    public static final List<String> SOURCES = List.of("HL7_ADT", "FHIR", "MANUAL");
    private final DocumentStore store;

    public HostDemographics(DocumentStore store) { this.store = store; }

    /** HL7 v2 table 0001 / FHIR administrative-gender → partition sex (male / female) or null; the raw code is kept. */
    public static String sex(String code) {
        if (code == null) return null;
        return switch (code.trim().toUpperCase()) {
            case "M", "MALE" -> "male";
            case "F", "FEMALE" -> "female";
            default -> null; // U, O, A, N, X, unknown, other: no sex partition – defaults / 'any' intervals apply
        };
    }

    /** PID-7 (YYYY[MM[DD[HHMM…]]]) or ISO date → ISO date; partial dates use the first day (age precision ±1 y / month). */
    public static String birthDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        try {
            if (s.contains("-")) return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s).toString();
            String d = s.replaceAll("[^0-9].*$", "");
            if (d.length() >= 8) return LocalDate.parse(d.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE).toString();
            if (d.length() == 6) return LocalDate.of(Integer.parseInt(d.substring(0, 4)), Integer.parseInt(d.substring(4, 6)), 1).toString();
            if (d.length() == 4) return LocalDate.of(Integer.parseInt(d), 1, 1).toString();
        } catch (RuntimeException ignored) { /* invalid date → not applied */ }
        return null;
    }

    /** HL7 TS (MSH-7 / EVN-2) → instant; null when absent or unparseable. */
    public static Instant hl7Time(String ts) {
        if (ts == null || ts.length() < 8) return null;
        try {
            String d = ts.replaceAll("[^0-9].*$", "");
            String p = (d + "000000").substring(0, 14);
            return LocalDateTime.parse(p, DateTimeFormatter.ofPattern("yyyyMMddHHmmss")).toInstant(ZoneOffset.UTC);
        } catch (RuntimeException e) { return null; }
    }

    public record Applied(String subject, List<String> updated, List<String> kept, boolean created) {
        public Map<String, Object> summary() { return Map.of("updated", updated, "kept", kept, "created", created); }
    }

    /**
     * Applies host demographics to the registry entry of {@code subject} (created when unknown).
     *
     * @param at host time of the information (message / resource time); null → now
     */
    public Applied apply(String tenant, String subject, String birthDateRaw, String sexRaw, String source, Instant at, String controlId) {
        if (subject == null || subject.isBlank()) return new Applied(subject, List.of(), List.of(), false);
        if (!SOURCES.contains(source)) throw new IllegalArgumentException("source");
        Instant when = at == null ? Instant.now() : at;
        ObjectNode s = store.get(tenant, "subject", subject).orElse(null);
        boolean created = s == null;
        if (s == null) {
            s = Json.obj().put("id", subject).put("mrn", subject).put("name", "").put("badge", "");
            s.putArray("vendorIds");
        }
        ObjectNode prov = s.withObject("demographicsProvenance");
        List<String> updated = new ArrayList<>(), kept = new ArrayList<>();
        String bd = birthDate(birthDateRaw);
        if (bd != null) set(s, prov, "birthDate", bd, null, source, when, controlId, updated, kept);
        if (sexRaw != null && !sexRaw.isBlank()) set(s, prov, "sex", sex(sexRaw), sexRaw.trim().toUpperCase(), source, when, controlId, updated, kept);
        if (created || !updated.isEmpty()) store.put(tenant, "subject", subject, s, "ACTIVE", null);
        return new Applied(subject, updated, kept, created);
    }

    private static void set(ObjectNode s, ObjectNode prov, String field, String value, String rawCode, String source, Instant at, String controlId, List<String> updated, List<String> kept) {
        JsonNode p = prov.get(field);
        // a manual entry keeps precedence only while it is newer than the host information
        if (p != null && "MANUAL".equals(p.path("source").asString()) && !"MANUAL".equals(source) && Instant.parse(p.path("at").asString("1970-01-01T00:00:00Z")).isAfter(at)) {
            kept.add(field);
            return;
        }
        // never let an older host message overwrite newer host information
        if (p != null && !"MANUAL".equals(p.path("source").asString()) && Instant.parse(p.path("at").asString("1970-01-01T00:00:00Z")).isAfter(at)) {
            kept.add(field);
            return;
        }
        if (value == null) s.remove(field); else s.put(field, value);
        if ("sex".equals(field)) { if (rawCode != null) s.put("administrativeSex", rawCode); }
        ObjectNode n = prov.putObject(field).put("source", source).put("at", at.toString());
        if (controlId != null) n.put("controlId", controlId);
        updated.add(field);
    }

    /** Manual registry entry (console): records MANUAL provenance with the entry time. */
    public static void markManual(ObjectNode s, String actor) {
        ObjectNode prov = s.withObject("demographicsProvenance");
        String now = Instant.now().toString();
        if (s.has("birthDate")) prov.putObject("birthDate").put("source", "MANUAL").put("at", now).put("by", actor);
        if (s.has("sex")) prov.putObject("sex").put("source", "MANUAL").put("at", now).put("by", actor);
    }
}
