package io.intimed.intelligence;

import io.intimed.clinical.Demographics;
import io.intimed.clinical.ReferenceRanges;
import io.intimed.common.ApiException;
import io.intimed.common.Json;
import io.intimed.engine.driver.DriverPackage;
import io.intimed.engine.driver.DriverRegistry;
import io.intimed.engine.parse.Parsed;
import io.intimed.engine.parse.ParserRegistry;
import io.intimed.engine.pipeline.Pipeline;
import io.intimed.engine.sim.Simulator;
import io.intimed.engine.terminology.Terminology;
import io.intimed.templates.TemplateRegistry;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Physiology-aware cohort simulator (IM-E13): renders real device payloads (through the signed driver's golden session)
 * for a demographic cohort – neonate to older adult, pregnancy trimesters, altitude, fasting, occupational regulation –
 * with values drawn from the cohort's reference interval (or the cohort's scripted abnormal values), converted from
 * the knowledge-base unit into the device's unit, optionally with signal artefacts (noise, spike, drift, dropout,
 * duplicate, clock skew). The payload is then dry-run through the real pipeline and the resulting interpretation flags
 * are compared with the cohort's expected flags – precision evidence for the reference-range engine.
 */
@Service
public class CohortSimulator {
    public static final List<String> ARTEFACTS = List.of("NOISE", "SPIKE", "DRIFT", "DROPOUT", "DUPLICATE", "CLOCK_SKEW");
    private final JsonNode profiles = Json.resource("simulator/profiles.json");
    private final DriverRegistry drivers;
    private final ParserRegistry parsers;
    private final Terminology terms;
    private final ReferenceRanges ranges;
    private final Pipeline pipeline;
    private final TemplateRegistry templates;

    public CohortSimulator(DriverRegistry drivers, ParserRegistry parsers, Terminology terms, ReferenceRanges ranges, Pipeline pipeline, TemplateRegistry templates) {
        this.drivers = drivers;
        this.parsers = parsers;
        this.terms = terms;
        this.ranges = ranges;
        this.pipeline = pipeline;
        this.templates = templates;
    }

    public JsonNode profiles() { return profiles; }

    public JsonNode profile(String id) {
        for (JsonNode p : profiles) if (p.get("id").asString().equals(id)) return p;
        throw ApiException.notFound("cohort profile " + id);
    }

    /** Reference-interval entry each driver key resolves to (key → entry id). */
    public Map<String, String> concepts(DriverPackage d) {
        Map<String, String> m = new LinkedHashMap<>();
        for (JsonNode s : d.semantics()) {
            if ("text".equals(s.path("type").asString())) continue;
            JsonNode e = ranges.find(s, s.get("key").asString());
            if (e != null) m.put(s.get("key").asString(), e.get("id").asString());
        }
        return m;
    }

    /** Drivers able to express a concept key ("entry" or "entry@fieldId"). */
    public boolean covers(DriverPackage d, String concept) {
        String entry = concept.contains("@") ? concept.substring(0, concept.indexOf('@')) : concept;
        String field = concept.contains("@") ? concept.substring(concept.indexOf('@') + 1) : null;
        for (var e : concepts(d).entrySet()) if (e.getValue().equals(entry) && (field == null || field.equals(e.getKey()))) return true;
        return false;
    }

    /** Device unit per driver key, learnt by parsing the driver's nominal golden payload. */
    Map<String, String> deviceUnits(DriverPackage d) {
        Map<String, String> u = new HashMap<>();
        try {
            String nominal = Simulator.render(d, Simulator.values(d), "MRN-SIM", Instant.parse("2026-01-01T10:00:00Z"), d.tests().path("serial").asString("SIM-0001"));
            for (Parsed p : parsers.get(d.grammar().get("type").asString()).parse(d.grammar(), nominal))
                p.values.forEach((k, r) -> u.putIfAbsent(k, terms.ucum(r.unit())));
        } catch (RuntimeException ignored) { /* fall back to semantic UCUM */ }
        for (JsonNode s : d.semantics()) u.putIfAbsent(s.get("key").asString(), s.path("ucum").asString(null));
        u.replaceAll((k, v) -> v == null ? d.semantic(k) == null ? null : d.semantic(k).path("ucum").asString(null) : v);
        return u;
    }

    private BigDecimal toDevice(BigDecimal v, String kbUnit, String deviceUnit, JsonNode sem) {
        Map<String, Double> f = new HashMap<>();
        sem.path("factors").properties().forEach(x -> {
            f.put(x.getKey(), x.getValue().asDouble());
            String[] p = x.getKey().split(">");
            if (p.length == 2) f.put(p[1] + ">" + p[0], 1.0 / x.getValue().asDouble());
        });
        return terms.convert(v, kbUnit, deviceUnit, f).orElse(null);
    }

    public ObjectNode simulate(String tenant, String driverRef, String profileId, List<String> artefacts, long seed) {
        return simulate(tenant, driverRef, profileId, artefacts, seed, null, Instant.parse("2026-03-02T09:30:00Z"), null);
    }

    /**
     * @param overrides concept (KB entry id) → value in the knowledge-base unit, replacing the cohort script (trajectories)
     * @param baseTs    device timestamp of the payload
     * @param subject   subject identifier rendered into the payload (null → synthetic MRN-SIM-…)
     */
    public ObjectNode simulate(String tenant, String driverRef, String profileId, List<String> artefacts, long seed, Map<String, BigDecimal> overrides, Instant baseTs, String subject) {
        DriverPackage d = drivers.require(tenant, driverRef);
        JsonNode p = profile(profileId);
        Demographics demo = Demographics.of(p.get("demographics"));
        Map<String, String> values = new LinkedHashMap<>(Simulator.values(d));
        Map<String, String> units = deviceUnits(d);
        ArrayNode trace = Json.arr();
        Random rnd = new Random(seed);
        for (var c : concepts(d).entrySet()) {
            String key = c.getKey();
            JsonNode entry = ranges.entry(c.getValue());
            JsonNode sem = d.semantic(key);
            JsonNode scripted = p.path("concepts").has(c.getValue() + "@" + key) ? p.path("concepts").get(c.getValue() + "@" + key) : p.path("concepts").get(c.getValue());
            if (overrides != null && overrides.containsKey(c.getValue())) scripted = Json.obj().put("v", overrides.get(c.getValue())).get("v");
            BigDecimal kbValue;
            String basis;
            if (scripted != null && scripted.isNumber()) { kbValue = scripted.decimalValue(); basis = overrides != null && overrides.containsKey(c.getValue()) ? "trajectory" : "cohort script"; }
            else {
                ReferenceRanges.Resolved r = ranges.resolve(entry, demo);
                if (r.gap() || r.low() == null || r.high() == null) { trace.addObject().put("key", key).put("concept", c.getValue()).put("basis", "driver default (no interval)").put("value", values.get(key)); continue; }
                double mid = (r.low().doubleValue() + r.high().doubleValue()) / 2, span = r.high().doubleValue() - r.low().doubleValue();
                kbValue = BigDecimal.valueOf(mid + (rnd.nextDouble() - 0.5) * span * 0.2);
                basis = "reference interval midpoint ±10 % (" + r.partition() + ")";
            }
            String devUnit = units.get(key);
            BigDecimal dv = toDevice(kbValue, entry.get("unit").asString(), devUnit, sem);
            if (dv == null) { trace.addObject().put("key", key).put("concept", c.getValue()).put("basis", "no conversion " + entry.get("unit").asString() + " → " + devUnit + "; driver default kept"); continue; }
            int scale = Math.max(0, new BigDecimal(values.getOrDefault(key, "0").replace(',', '.')).scale());
            if (scripted != null) scale = Math.max(scale, Math.max(0, scripted.decimalValue().scale()));
            String s = dv.setScale(scale, RoundingMode.HALF_UP).toPlainString();
            values.put(key, s);
            trace.addObject().put("key", key).put("concept", c.getValue()).put("basis", basis).put("kbValue", kbValue.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + " " + entry.get("unit").asString())
                    .put("deviceValue", s + " " + (devUnit == null ? "" : devUnit));
        }
        List<String> applied = new ArrayList<>();
        Instant ts = baseTs;
        if (artefacts != null) for (String a : artefacts) {
            switch (a) {
                case "NOISE" -> values.replaceAll((k, v) -> concepts(d).containsKey(k) ? scale(v, 1 + (rnd.nextDouble() - 0.5) * 0.06) : v);
                case "SPIKE" -> concepts(d).keySet().stream().findFirst().ifPresent(k -> values.computeIfPresent(k, (kk, v) -> scale(v, 1.8)));
                case "DRIFT" -> values.replaceAll((k, v) -> concepts(d).containsKey(k) ? scale(v, 1.08) : v);
                case "DROPOUT" -> concepts(d).keySet().stream().skip(1).findFirst().ifPresent(k -> values.put(k, ""));
                case "CLOCK_SKEW" -> ts = ts.plusSeconds(7200);
                default -> { }
            }
            applied.add(a);
        }
        String serial = d.tests().path("serial").asString("SIM-0001");
        String payload = Simulator.render(d, values, subject != null ? subject : "MRN-SIM-" + Math.floorMod(profileId.hashCode(), 1000), ts, serial);
        ObjectNode out = Json.obj().put("driver", d.ref()).put("profile", profileId).put("profileName", p.path("name").asString())
                .put("demographics", demo.describe()).put("ageBand", ranges.ageBand(demo.ageYears())).put("seed", seed);
        out.set("artefacts", Json.tree(applied));
        out.set("generation", trace);
        out.put("payload", payload);
        if (applied.contains("DUPLICATE")) out.put("duplicateOf", "payload sent twice – the live pipeline acknowledges the second copy as DUPLICATE (idempotent stable id)");
        if (applied.contains("CLOCK_SKEW")) out.put("clockSkew", "device clock +2 h – the quality gate holds observations with > 5 min skew in live mode");
        ObjectNode dry = pipeline.dryRun(tenant, d, payload, null, id -> templates.find(tenant, id).orElse(null), demo);
        out.set("dryRun", dry);
        // compare expected flags
        ArrayNode cmp = out.putArray("expected");
        int match = 0, total = 0;
        for (var e : p.path("expected").properties()) {
            String concept = e.getKey();
            if (!covers(d, concept)) continue;
            String entry = concept.contains("@") ? concept.substring(0, concept.indexOf('@')) : concept;
            String field = concept.contains("@") ? concept.substring(concept.indexOf('@') + 1) : null;
            String actual = null, where = null;
            for (JsonNode set : dry.path("sets")) for (JsonNode m : set.path("mapped")) for (var fv : m.path("fields").properties()) {
                if (actual != null) break;
                if (entry.equals(fv.getValue().at("/referenceRange/entry").asString(null)) && (field == null || field.equals(fv.getValue().path("sourceKey").asString()))) {
                    actual = fv.getValue().at("/interpretation/code").asString(null);
                    where = m.path("templateId").asString() + "/" + fv.getKey();
                }
            }
            total++;
            boolean ok = e.getValue().asString().equals(actual);
            if (ok) match++;
            cmp.addObject().put("concept", concept).put("expected", e.getValue().asString()).put("actual", actual).put("field", where).put("match", ok);
        }
        out.put("expectedTotal", total).put("expectedMatched", match);
        return out;
    }

    private static String scale(String v, double f) {
        try {
            BigDecimal b = new BigDecimal(v);
            return b.multiply(BigDecimal.valueOf(f)).setScale(Math.max(0, b.scale()), RoundingMode.HALF_UP).toPlainString();
        } catch (NumberFormatException e) { return v; }
    }

    // ------------------------------------------------------------------------------------------ time series (IM-E13-BL-005)
    /** Piecewise-linear interpolation of trajectory keyframes ({minute, values}) at a minute; held constant outside. */
    public static Map<String, Double> interpolate(JsonNode trajectory, double minute) {
        Map<String, Double> out = new LinkedHashMap<>();
        List<JsonNode> kf = new ArrayList<>();
        trajectory.forEach(kf::add);
        kf.sort(java.util.Comparator.comparingDouble(k -> k.path("minute").asDouble()));
        Set<String> concepts = new LinkedHashSet<>();
        kf.forEach(k -> concepts.addAll(k.path("values").propertyNames()));
        for (String c : concepts) {
            JsonNode before = null, after = null;
            for (JsonNode k : kf) {
                if (!k.path("values").has(c)) continue;
                if (k.path("minute").asDouble() <= minute) before = k;
                if (k.path("minute").asDouble() >= minute && after == null) after = k;
            }
            if (before == null) before = after;
            if (after == null) after = before;
            if (before == null) continue;
            double m0 = before.path("minute").asDouble(), m1 = after.path("minute").asDouble();
            double v0 = before.path("values").get(c).asDouble(), v1 = after.path("values").get(c).asDouble();
            out.put(c, m1 == m0 ? v0 : v0 + (v1 - v0) * (minute - m0) / (m1 - m0));
        }
        return out;
    }

    /** Last keyframe minute of a trajectory. */
    public static int duration(JsonNode trajectory) {
        int m = 0;
        for (JsonNode k : trajectory) m = Math.max(m, k.path("minute").asInt());
        return m;
    }

    /** Driver covering most trajectory concepts, preferring continuous drivers. */
    public DriverPackage driverForTrajectory(String tenant, JsonNode profile) {
        Set<String> need = new LinkedHashSet<>();
        profile.path("trajectory").forEach(k -> need.addAll(k.path("values").propertyNames()));
        DriverPackage best = null;
        long bestScore = -1;
        for (DriverPackage d : drivers.all(tenant)) {
            if (d.restricted()) continue;
            long n = need.stream().filter(c -> covers(d, c)).count() * 2 + ("CONTINUOUS".equals(d.manifest().path("captureMode").asString()) ? 1 : 0);
            if (n > bestScore) { best = d; bestScore = n; }
        }
        return best;
    }

    /**
     * Renders a trajectory as N payloads at an interval through a driver and the real dry-run pipeline: per step the
     * interpolated values (+ seeded relative noise {@code noisePct}), the resulting interpretation flags and a NEWS2-style
     * aggregate (informational only – IntiMed raises no alarm; consciousness/oxygen assumed alert/air).
     */
    public ObjectNode trajectory(String tenant, String driverRef, String profileId, int steps, int intervalMinutes, long seed, Instant start, String subject) {
        JsonNode p = profile(profileId);
        if (!p.path("trajectory").isArray() || p.path("trajectory").isEmpty()) throw ApiException.bad("no-trajectory", "profile " + profileId + " defines no trajectory");
        DriverPackage d = driverRef == null || driverRef.isBlank() ? driverForTrajectory(tenant, p) : drivers.require(tenant, driverRef);
        int iv = Math.max(1, Math.min(intervalMinutes, 240));
        int n = steps <= 0 ? duration(p.get("trajectory")) / iv + 1 : Math.min(steps, 500);
        double noise = p.path("noisePct").asDouble(0);
        Random rnd = new Random(seed);
        ObjectNode out = Json.obj().put("profile", profileId).put("name", p.path("name").asString()).put("driver", d.ref())
                .put("captureMode", d.manifest().path("captureMode").asString("SPOT")).put("intervalMinutes", iv).put("steps", n).put("noisePct", noise).put("seed", seed)
                .put("demographics", Demographics.of(p.get("demographics")).describe())
                .put("disclaimer", "Engineering simulation (piecewise-linear keyframes + seeded noise) – not a validated physiological model; NEWS2 shown for information only, IntiMed raises no alarms.");
        Set<String> covered = new LinkedHashSet<>(), uncovered = new LinkedHashSet<>();
        p.path("trajectory").forEach(k -> k.path("values").propertyNames().forEach(c -> (covers(d, c) ? covered : uncovered).add(c)));
        out.set("coveredConcepts", Json.tree(new ArrayList<>(covered)));
        out.set("notCoveredByDriver", Json.tree(new ArrayList<>(uncovered)));
        ArrayNode rows = out.putArray("series");
        Integer firstAbnormal = null;
        int maxNews = -1;
        for (int i = 0; i < n; i++) {
            int minute = i * iv;
            Map<String, BigDecimal> ov = new LinkedHashMap<>();
            for (var e : interpolate(p.get("trajectory"), minute).entrySet()) {
                double v = e.getValue() * (1 + rnd.nextGaussian() * noise / 100.0);
                ov.put(e.getKey(), BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP));
            }
            ObjectNode sim = simulate(tenant, d.ref(), profileId, List.of(), seed + i, ov, start.plusSeconds(minute * 60L), subject);
            ObjectNode row = rows.addObject().put("step", i).put("minute", minute).put("at", start.plusSeconds(minute * 60L).toString());
            ObjectNode vals = row.putObject("values"), flags = row.putObject("flags");
            for (var e : ov.entrySet()) if (covered.contains(e.getKey())) vals.put(e.getKey(), e.getValue());
            for (JsonNode set : sim.at("/dryRun/sets")) for (JsonNode m : set.path("mapped")) for (var fv : m.path("fields").properties()) {
                String entry = fv.getValue().at("/referenceRange/entry").asString(null);
                String code = fv.getValue().at("/interpretation/code").asString(null);
                if (entry != null && code != null && !flags.has(entry)) flags.put(entry, code);
            }
            boolean abnormal = false;
            for (var f : flags.properties()) abnormal |= !"N".equals(f.getValue().asString());
            if (abnormal && firstAbnormal == null) firstAbnormal = minute;
            Map<String, Double> news = new LinkedHashMap<>();
            for (var e : ov.entrySet()) if (covered.contains(e.getKey())) news.put(e.getKey(), e.getValue().doubleValue());
            ObjectNode n2 = io.intimed.clinical.News2.score(news);
            row.set("news2", n2);
            maxNews = Math.max(maxNews, n2.path("score").asInt());
            row.put("payload", sim.get("payload").asString());
        }
        ObjectNode sum = out.putObject("summary").put("maxNews2", maxNews);
        if (firstAbnormal != null) sum.put("firstAbnormalFlagMinute", firstAbnormal);
        return out;
    }

    /** Smallest set of drivers covering the cohort's expected concepts (greedy). */
    public List<DriverPackage> driversFor(String tenant, JsonNode profile) {
        Set<String> need = new LinkedHashSet<>();
        profile.path("expected").properties().forEach(e -> need.add(e.getKey()));
        List<DriverPackage> chosen = new ArrayList<>();
        List<DriverPackage> all = drivers.all(tenant).stream().filter(x -> !x.restricted()).toList();
        while (!need.isEmpty()) {
            DriverPackage best = null;
            long bestN = 0;
            for (DriverPackage d : all) {
                long n = need.stream().filter(c -> covers(d, c)).count();
                if (n > bestN) { best = d; bestN = n; }
            }
            if (best == null) break;
            DriverPackage b = best;
            need.removeIf(c -> covers(b, c));
            chosen.add(best);
        }
        return chosen;
    }

    /** Runs every scripted cohort through its covering drivers – the precision report shown in the studio and XT-17. */
    public ObjectNode runAll(String tenant) {
        ObjectNode out = Json.obj();
        ArrayNode rows = out.putArray("cohorts");
        int total = 0, match = 0;
        for (JsonNode p : profiles) {
            if (p.path("expected").isEmpty()) continue;
            for (DriverPackage d : driversFor(tenant, p)) {
                ObjectNode r = simulate(tenant, d.ref(), p.get("id").asString(), List.of(), 42);
                ObjectNode row = rows.addObject().put("profile", p.get("id").asString()).put("name", p.path("name").asString()).put("driver", d.ref())
                        .put("matched", r.get("expectedMatched").asInt()).put("total", r.get("expectedTotal").asInt());
                row.set("expected", r.get("expected"));
                total += r.get("expectedTotal").asInt();
                match += r.get("expectedMatched").asInt();
            }
        }
        out.put("total", total).put("matched", match).put("precision", total == 0 ? 0 : Math.round(1000.0 * match / total) / 1000.0);
        return out;
    }
}
