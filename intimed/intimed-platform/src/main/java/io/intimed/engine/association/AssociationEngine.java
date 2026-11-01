package io.intimed.engine.association;

import io.intimed.common.Json;
import io.intimed.store.DocumentStore;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Association engine (IM-E01-F05 / IM-E01-BL-021): links a device result to the right subject by the configured
 * method – WORKLIST (order at the station), ENCOUNTER_BED (ADT bed binding with transfer/discharge), SESSION (capture
 * session started by the clinician), BADGE (badge/NFC/QR identifier), MATCH (vendor-typed id: exact 0.97, normalised 0.92,
 * fuzzy 0.85), CONSENT (RPM device-serial enrolment with consent). Below the auto-file threshold the result goes to the
 * unmatched queue with suggestions; restricted categories (drug & alcohol) never use identifier matching.
 */
@Service
public class AssociationEngine {
    public static final double EXACT = 0.97, NORMALISED = 0.92, FUZZY = 0.85, SUGGEST = 0.60;
    private final DocumentStore store;

    public AssociationEngine(DocumentStore store) { this.store = store; }

    public record Result(String subject, String method, double confidence, String detail, String suggestion, String consentScope) {
        public boolean matched(double threshold) { return subject != null && confidence >= threshold; }
        static Result none(String method, String detail) { return new Result(null, method, 0, detail, null, null); }
    }

    public Result associate(String tenant, JsonNode config, Map<String, String> identity, String testSubject, Instant at) {
        return associate(tenant, config, identity, testSubject, at, false);
    }

    /** {@code harnessProbe}: exchange-test traffic never consults live association context (worklists, beds, sessions, enrolments). */
    public Result associate(String tenant, JsonNode config, Map<String, String> identity, String testSubject, Instant at, boolean harnessProbe) {
        if (testSubject != null) return new Result(testSubject, "TEST_SUBJECT", 1.0, "harness test subject", null, null);
        if (harnessProbe) return Result.none(config.path("association").path("method").asString("MATCH"), "harness unmatched-path probe – live association context isolated");
        JsonNode a = config.path("association");
        String method = a.path("method").asString("MATCH");
        boolean restricted = config.path("restricted").asBoolean(false);
        String station = config.at("/site/station").asString(null);
        switch (method) {
            case "ENCOUNTER_BED" -> {
                String bed = config.at("/site/bed").asString(null);
                Optional<ObjectNode> b = bed == null ? Optional.empty() : store.get(tenant, "bed", bed);
                if (b.isPresent() && b.get().hasNonNull("subject")) {
                    return new Result(b.get().get("subject").asString(), method, 0.99, "bed " + bed + " encounter " + b.get().path("encounter").asString(""), null, null);
                }
                return Result.none(method, "no active encounter on bed " + bed);
            }
            case "WORKLIST" -> {
                Optional<ObjectNode> w = openAt(tenant, "worklist", station, at);
                if (w.isPresent()) return new Result(w.get().get("subject").asString(), method, 0.99, "worklist " + w.get().get("id").asString(), null, null);
                Optional<ObjectNode> s = openAt(tenant, "capture", station, at);
                if (s.isPresent() && a.path("sessionFallback").asBoolean(true)) return new Result(s.get().get("subject").asString(), "SESSION", 0.98, "session fallback " + s.get().get("id").asString(), null, null);
                if (!restricted && identity.containsKey("badge")) return badge(tenant, identity.get("badge"));
                return Result.none(method, "no open worklist or session at station " + station);
            }
            case "SESSION" -> {
                Optional<ObjectNode> s = openAt(tenant, "capture", station, at);
                if (s.isPresent()) return new Result(s.get().get("subject").asString(), method, 0.98, "session " + s.get().get("id").asString(), null, null);
                return Result.none(method, "no capture session at station " + station);
            }
            case "BADGE" -> {
                if (restricted) return Result.none(method, "restricted category: badge association not allowed");
                return identity.containsKey("badge") ? badge(tenant, identity.get("badge")) : Result.none(method, "no badge in message");
            }
            case "CONSENT" -> {
                String serial = identity.get("serial");
                for (ObjectNode e : store.list(tenant, "enrolment")) {
                    if (serial != null && serial.equals(e.path("deviceSerial").asString())) {
                        JsonNode c = e.path("consent");
                        if (!c.path("given").asBoolean(false) || Instant.parse(c.path("expires").asString("1970-01-01T00:00:00Z")).isBefore(at)) {
                            return new Result(null, method, 0, "consent missing or expired for enrolled device", null, "NO_CONSENT");
                        }
                        return new Result(e.get("subject").asString(), method, 0.99, "enrolment " + e.get("id").asString(), null, c.path("scopes").toString());
                    }
                }
                return Result.none(method, "device serial not enrolled");
            }
            default -> {
                if (restricted) return Result.none(method, "restricted category: identifier matching not allowed");
                return match(tenant, identity.get("subject"));
            }
        }
    }

    private Optional<ObjectNode> openAt(String tenant, String kind, String station, Instant at) {
        if (station == null) return Optional.empty();
        return store.listByStatus(tenant, kind, "OPEN").stream()
                .filter(w -> station.equals(w.path("station").asString()))
                .filter(w -> !w.has("expiresAt") || Instant.parse(w.get("expiresAt").asString()).isAfter(at))
                .max(Comparator.comparing(w -> w.path("createdAt").asString("")));
    }

    private Result badge(String tenant, String badge) {
        for (ObjectNode s : store.list(tenant, "subject")) if (badge.equals(s.path("badge").asString())) return new Result(s.get("id").asString(), "BADGE", 0.98, "badge", null, null);
        return Result.none("BADGE", "unknown badge");
    }

    Result match(String tenant, String typed) {
        if (typed == null || typed.isBlank()) return Result.none("MATCH", "no subject identifier in message");
        List<ObjectNode> subjects = store.list(tenant, "subject");
        String best = null;
        double conf = 0;
        String how = "";
        for (ObjectNode s : subjects) {
            for (String id : ids(s)) {
                double c = 0;
                String h = "";
                if (id.equals(typed)) { c = EXACT; h = "exact"; }
                else if (norm(id).equals(norm(typed))) { c = NORMALISED; h = "normalised"; }
                else if (norm(id).length() >= 6 && lev(norm(id), norm(typed)) == 1) { c = FUZZY; h = "fuzzy (1 edit)"; }
                if (c > conf) { conf = c; best = s.get("id").asString(); how = h; }
            }
        }
        if (best == null) return Result.none("MATCH", "no candidate for vendor-typed identifier");
        return new Result(best, "MATCH", conf, how, conf < 0.95 && conf >= SUGGEST ? best : null, null);
    }

    private static List<String> ids(ObjectNode s) {
        List<String> l = new java.util.ArrayList<>();
        l.add(s.path("mrn").asString(""));
        s.path("vendorIds").forEach(v -> l.add(v.asString()));
        return l.stream().filter(x -> !x.isBlank()).toList();
    }

    static String norm(String s) { return s.toUpperCase().replaceAll("[^A-Z0-9]", "").replaceFirst("^0+", ""); }

    static int lev(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    // ---------------------------------------------------------------- context maintenance (ADT, worklists, sessions, enrolment)
    public ObjectNode admit(String tenant, String bed, String subject, String encounter) {
        ObjectNode b = Json.obj().put("bed", bed).put("subject", subject).put("encounter", encounter).put("since", Instant.now().toString());
        return store.put(tenant, "bed", bed, b, "OCCUPIED", null);
    }

    public void transfer(String tenant, String fromBed, String toBed) {
        store.get(tenant, "bed", fromBed).ifPresent(b -> {
            store.delete(tenant, "bed", fromBed);
            b.put("bed", toBed).put("since", Instant.now().toString());
            store.put(tenant, "bed", toBed, b, "OCCUPIED", null);
        });
    }

    public void discharge(String tenant, String bed) { store.delete(tenant, "bed", bed); }

    public ObjectNode openWorklist(String tenant, String station, String subject, String templateId, String orderRef, int minutes) {
        String id = io.intimed.common.Hashing.id("wl");
        ObjectNode w = Json.obj().put("id", id).put("station", station).put("subject", subject).put("templateId", templateId)
                .put("orderRef", orderRef).put("createdAt", Instant.now().toString()).put("expiresAt", Instant.now().plusSeconds(minutes * 60L).toString()).put("status", "OPEN");
        return store.put(tenant, "worklist", id, w, "OPEN", station);
    }

    public ObjectNode openSession(String tenant, String station, String subject, String templateId, String user, int minutes) {
        for (ObjectNode s : store.listByStatus(tenant, "capture", "OPEN")) {
            if (station.equals(s.path("station").asString())) { s.put("status", "CLOSED"); store.put(tenant, "capture", s.get("id").asString(), s, "CLOSED", station); }
        }
        String id = io.intimed.common.Hashing.id("ses");
        ObjectNode s = Json.obj().put("id", id).put("station", station).put("subject", subject).put("templateId", templateId).put("startedBy", user)
                .put("createdAt", Instant.now().toString()).put("expiresAt", Instant.now().plusSeconds(minutes * 60L).toString()).put("status", "OPEN");
        return store.put(tenant, "capture", id, s, "OPEN", station);
    }

    public void closeSession(String tenant, String id) {
        store.get(tenant, "capture", id).ifPresent(s -> { s.put("status", "CLOSED"); store.put(tenant, "capture", id, s, "CLOSED", s.path("station").asString()); });
    }

    public ObjectNode enrol(String tenant, String deviceSerial, String subject, String configId, List<String> scopes, int days) {
        String id = io.intimed.common.Hashing.id("enr");
        ObjectNode e = Json.obj().put("id", id).put("deviceSerial", deviceSerial).put("subject", subject).put("configId", configId);
        e.putObject("consent").put("given", true).put("givenAt", Instant.now().toString()).put("expires", Instant.now().plusSeconds(days * 86400L).toString())
                .set("scopes", Json.tree(scopes));
        return store.put(tenant, "enrolment", id, e, "ACTIVE", null);
    }

    public void withdrawConsent(String tenant, String enrolmentId) {
        store.get(tenant, "enrolment", enrolmentId).ifPresent(e -> {
            ((ObjectNode) e.get("consent")).put("given", false).put("withdrawnAt", Instant.now().toString());
            store.put(tenant, "enrolment", enrolmentId, e, "WITHDRAWN", null);
        });
    }
}
