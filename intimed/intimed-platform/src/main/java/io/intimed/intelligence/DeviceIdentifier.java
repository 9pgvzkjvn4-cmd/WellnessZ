package io.intimed.intelligence;

import io.intimed.common.Json;
import io.intimed.engine.driver.DriverPackage;
import io.intimed.engine.driver.DriverRegistry;
import io.intimed.engine.parse.Parsed;
import io.intimed.engine.parse.ParserRegistry;
import io.intimed.engine.sim.Simulator;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Device fingerprinting (IM-E12-F01): identifies device type, model, integration option and driver from a captured
 * payload – no configuration needed. Evidence per candidate: grammar parse success, share of driver keys recovered,
 * structural token similarity with the driver's golden session (segment ids, XML elements, JSON property names,
 * vendor application ids, GATT characteristic) and identity hints (sending application, serial-number pattern).
 * Returns ranked candidates with a calibrated confidence and the evidence, so the wizard can pre-select model/option.
 */
@Service
public class DeviceIdentifier {
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z_][A-Za-z0-9_.:-]{2,}");
    private final DriverRegistry drivers;
    private final ParserRegistry parsers;
    private final IntegrationProfiles profiles;
    private final Map<String, Set<String>> fingerprints = new HashMap<>();

    public DeviceIdentifier(DriverRegistry drivers, ParserRegistry parsers, IntegrationProfiles profiles) {
        this.drivers = drivers;
        this.parsers = parsers;
        this.profiles = profiles;
    }

    static Set<String> tokens(String s) {
        Set<String> t = new HashSet<>();
        Matcher m = TOKEN.matcher(s);
        while (m.find()) {
            String x = m.group();
            if (x.matches(".*\\d{4,}.*")) continue; // timestamps, ids, serial numbers vary per message
            t.add(x.toLowerCase());
        }
        return t;
    }

    private Set<String> fingerprint(DriverPackage d) {
        return fingerprints.computeIfAbsent(d.ref(), k -> {
            Set<String> t = new HashSet<>();
            for (JsonNode g : d.tests().path("golden")) {
                try { t.addAll(tokens(Simulator.render(d, Simulator.values(d), "MRN-FP", Instant.parse("2026-01-01T10:00:00Z"), "FP-SERIAL"))); }
                catch (RuntimeException e) { t.addAll(tokens(g.toString())); }
                break;
            }
            t.removeAll(Set.of("mrn-fp", "fp-serial"));
            return t;
        });
    }

    public ObjectNode identify(String tenant, String payload) {
        Set<String> pt = tokens(payload);
        List<ObjectNode> cands = new ArrayList<>();
        for (DriverPackage d : drivers.all(tenant)) {
            ObjectNode c = Json.obj().put("driver", d.ref()).put("name", d.name()).put("family", d.family());
            ArrayNode ev = c.putArray("evidence");
            double parseOk = 0, coverage = 0;
            int recovered = 0, numericKeys = 0;
            for (JsonNode s : d.semantics()) if (!"text".equals(s.path("type").asString())) numericKeys++;
            try {
                List<Parsed> sets = parsers.get(d.grammar().get("type").asString()).parse(d.grammar(), payload);
                parseOk = sets.isEmpty() ? 0.3 : 1;
                Set<String> keys = new HashSet<>();
                for (Parsed p : sets) keys.addAll(p.values.keySet());
                for (String k : keys) if (d.semantic(k) != null) recovered++;
                coverage = d.semantics().size() == 0 ? 0 : (double) recovered / d.semantics().size();
                ev.add("grammar " + d.grammar().get("type").asString() + " parsed " + sets.size() + " set(s)");
                ev.add(recovered + "/" + d.semantics().size() + " driver keys recovered");
                if (sets.size() > 0 && sets.get(0).identity.containsKey("serial")) ev.add("device serial present");
            } catch (RuntimeException e) {
                ev.add("grammar " + d.grammar().get("type").asString() + " rejected: " + truncate(e.getMessage()));
            }
            Set<String> fp = fingerprint(d);
            Set<String> inter = new HashSet<>(fp);
            inter.retainAll(pt);
            Set<String> union = new HashSet<>(fp);
            union.addAll(pt);
            double jaccard = union.isEmpty() ? 0 : (double) inter.size() / union.size();
            ev.add(String.format("structural similarity %.2f (%d shared tokens)", jaccard, inter.size()));
            double hint = 0;
            for (var opt : d.json().path("transport").properties()) {
                String app = opt.getValue().path("parameters").path("sendingApplication").asString(null);
                if (app != null && payload.contains(app)) { hint = 1; ev.add("sending application " + app + " (" + opt.getKey() + ")"); }
                String ch = opt.getValue().path("parameters").path("characteristic").asString(null);
                if (ch != null && payload.contains(ch)) { hint = 1; ev.add("GATT characteristic " + ch); }
            }
            String serial = d.tests().path("serial").asString("");
            if (serial.length() > 4 && payload.contains(serial.substring(0, 4))) { hint = Math.max(hint, 0.5); ev.add("serial-number prefix " + serial.substring(0, 4)); }
            double score = 0.25 * parseOk + 0.35 * coverage + 0.3 * Math.min(1, jaccard * 1.6) + 0.1 * hint;
            if (parseOk == 0) score *= 0.4;
            c.put("score", Math.round(score * 1000) / 1000.0).put("parsed", parseOk > 0).put("keysRecovered", recovered).put("numericKeys", numericKeys)
                    .put("similarity", Math.round(jaccard * 1000) / 1000.0);
            c.set("models", Json.tree(d.models()));
            c.set("options", Json.tree(d.options()));
            List<String> types = new ArrayList<>();
            for (String m : d.models()) { String t = profiles.deviceType(m); if (t != null && !types.contains(t)) types.add(t); }
            c.set("deviceTypes", Json.tree(types));
            cands.add(c);
        }
        cands.sort(Comparator.comparingDouble((ObjectNode c) -> c.get("score").asDouble()).reversed());
        ObjectNode out = Json.obj();
        double top = cands.isEmpty() ? 0 : cands.get(0).get("score").asDouble();
        double second = cands.size() > 1 ? cands.get(1).get("score").asDouble() : 0;
        double margin = top - second;
        double confidence = Math.min(0.99, top * (0.6 + Math.min(0.4, margin * 2)));
        out.put("confidence", Math.round(confidence * 100) / 100.0).put("margin", Math.round(margin * 1000) / 1000.0)
                .put("verdict", confidence >= 0.6 ? "IDENTIFIED" : confidence >= 0.35 ? "PROBABLE" : "UNKNOWN – capture more samples or use the driver SDK");
        if (!cands.isEmpty() && confidence >= 0.35) {
            ObjectNode best = cands.get(0);
            out.set("bestMatch", best);
            String model = best.get("models").path(0).asString(null);
            if (model != null) out.set("integrationProfile", profiles.forModel(tenant, model));
        }
        ArrayNode a = out.putArray("candidates");
        cands.stream().limit(5).forEach(a::add);
        return out;
    }

    private static String truncate(String s) { return s == null ? "" : s.length() > 80 ? s.substring(0, 80) + "…" : s; }

    /** Identification precision over every driver's golden session (top-1 accuracy) – evidence for IM-E12. */
    public ObjectNode benchmark(String tenant) {
        ObjectNode out = Json.obj();
        ArrayNode rows = out.putArray("results");
        int ok = 0, n = 0;
        for (DriverPackage d : drivers.all(tenant)) {
            String payload;
            try { payload = Simulator.render(d, Simulator.vary(Simulator.values(d), 99), "MRN-BENCH-7", Instant.parse("2026-02-03T08:15:00Z"), d.tests().path("serial").asString("BENCH-1")); }
            catch (RuntimeException e) { continue; }
            ObjectNode r = identify(tenant, payload);
            String got = r.path("candidates").path(0).path("driver").asString();
            boolean hit = got.equals(d.ref());
            n++;
            if (hit) ok++;
            rows.addObject().put("driver", d.ref()).put("identifiedAs", got).put("confidence", r.get("confidence").asDouble()).put("correct", hit);
        }
        out.put("total", n).put("top1", ok).put("accuracy", n == 0 ? 0 : Math.round(1000.0 * ok / n) / 1000.0);
        return out;
    }
}
