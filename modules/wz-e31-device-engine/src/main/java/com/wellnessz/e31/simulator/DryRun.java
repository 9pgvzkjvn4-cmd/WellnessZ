package com.wellnessz.e31.simulator;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceObservation;
import com.wellnessz.e31.adapter.Adapters;
import com.wellnessz.e31.adapter.ParseContext;
import com.wellnessz.e31.adapter.ParsedRecord;
import com.wellnessz.e31.driver.DriverPackage;
import com.wellnessz.e31.gate.GateContext;
import com.wellnessz.e31.gate.GateEnvironment;
import com.wellnessz.e31.gate.Gates;
import com.wellnessz.e31.pipeline.Normaliser;
import com.wellnessz.e31.routing.Router;
import com.wellnessz.e31.support.EngineException;
import com.wellnessz.e31.terminology.Terminology;

import tools.jackson.databind.JsonNode;

/**
 * In-memory pipeline run without persistence (simulator environment: calibrated, QC not required): PARSE → NORMALISE →
 * VALIDATE → binding preview. Used by driver conformance ({@code testVectors}), wizard test runs and unit tests.
 */
public final class DryRun {

    private DryRun() {
    }

    public record Record(List<DeviceObservation.Component> components, List<DeviceObservation.QualityFlag> flags, Map<String, Object> boundFields,
                         Instant effectiveAt, long clockSkewMillis, ParsedRecord.Identity identity) {

        public BigDecimal value(String key) {
            return components.stream().filter(c -> c.key().equals(key)).findFirst().map(DeviceObservation.Component::value).orElse(null);
        }

        public String text(String key) {
            return components.stream().filter(c -> c.key().equals(key)).findFirst().map(DeviceObservation.Component::text).orElse(null);
        }

        public DeviceCodes.GateOutcome outcome(String gate) {
            DeviceCodes.GateOutcome worst = null;
            for (DeviceObservation.QualityFlag f : flags) {
                if (f.gate().equals(gate) && (worst == null || f.outcome().ordinal() > worst.ordinal())) {
                    worst = f.outcome();
                }
            }
            return worst;
        }
    }

    /** @param error contract error code when the run failed (parse_failed, mapping_missing …) */
    public record Result(List<Record> records, String error, String errorMessage) {
    }

    public static Result run(Adapters adapters, Terminology terminology, DriverPackage pkg, byte[] body, String contentType, Map<String, String> headers,
                             Instant receivedAt, Instant edgeClock, GateEnvironment env, long maxSkewSeconds) {
        try {
            List<ParsedRecord> parsed = adapters.parse(new ParseContext(pkg, body, contentType, headers, receivedAt, ZoneOffset.UTC));
            List<Record> out = new ArrayList<>();
            for (ParsedRecord rec : parsed) {
                Normaliser.Result n = Normaliser.normalise(pkg, rec, terminology, new Normaliser.Context(receivedAt, null, edgeClock, maxSkewSeconds));
                Router.Selection sel = pkg.bindings().isEmpty() ? null : new Router.Selection(pkg.bindings().get(0).formCode(),
                        pkg.bindings().get(0).formVersion(), pkg.bindings().get(0).category(), pkg.bindings().get(0).target(), pkg.bindings().get(0).fields(),
                        pkg.bindings().get(0).verifyBeforePost(),
                        pkg.bindings().get(0).qualityGates().isEmpty() ? pkg.qualityGates() : pkg.bindings().get(0).qualityGates());
                DeviceCodes.Category cat = sel == null ? DeviceCodes.Category.VITALS : sel.category();
                GateContext ctx = new GateContext(pkg, cat, n.components(), "SIM", "SIM-EQUIPMENT", pkg.models().get(0), Map.of(),
                        rec.identity().employeeId(), n.effectiveAt(), n.clockSkewMillis(), maxSkewSeconds,
                        "OCR".equals(pkg.adapter()) ? DeviceCodes.CaptureMethod.OCR_VERIFIED : "MANUAL".equals(pkg.adapter())
                                ? DeviceCodes.CaptureMethod.MANUAL_ATTESTED : DeviceCodes.CaptureMethod.DEVICE,
                        null, "SIM-MODEL", "SIM-SERIAL", "SIM-CAL", receivedAt.atZone(ZoneOffset.UTC).toLocalDate(), BigDecimal.ONE, BigDecimal.valueOf(0.9),
                        sel == null || sel.verifyBeforePost(), env);
                List<DeviceObservation.QualityFlag> flags = Gates.run(sel == null ? pkg.qualityGates() : sel.qualityGates(), ctx);
                Map<String, Object> bound = sel == null ? Map.of() : Router.boundFields(sel, n.components(), n.derived(), Map.of());
                out.add(new Record(n.components(), flags, bound, n.effectiveAt(), n.clockSkewMillis(), rec.identity()));
            }
            return new Result(out, null, null);
        } catch (EngineException e) {
            return new Result(List.of(), e.code(), e.getMessage());
        }
    }

    /**
     * Compares a dry run with a test vector's {@code expect}: {@code error}, component values ({@code key → number|text}) and
     * {@code gates} ({@code gate → outcome}). Returns the differences (empty = pass).
     */
    public static List<String> diff(Result r, JsonNode expect) {
        List<String> diff = new ArrayList<>();
        if (expect == null) {
            return diff;
        }
        String error = expect.hasNonNull("error") ? expect.get("error").asString() : null;
        if (error != null) {
            if (!error.equals(r.error())) {
                diff.add("error: expected " + error + ", got " + (r.error() == null ? "none" : r.error()));
            }
            return diff;
        }
        if (r.error() != null) {
            diff.add("error: unexpected " + r.error());
            return diff;
        }
        if (r.records().isEmpty()) {
            diff.add("records: none");
            return diff;
        }
        Record rec = r.records().get(0);
        for (Map.Entry<String, JsonNode> e : expect.properties()) {
            if ("gates".equals(e.getKey()) || "error".equals(e.getKey())) {
                continue;
            }
            JsonNode v = e.getValue();
            if (v.isNumber()) {
                BigDecimal actual = rec.value(e.getKey());
                if (actual == null || actual.compareTo(v.decimalValue()) != 0) {
                    diff.add(e.getKey() + ": expected " + v.decimalValue().toPlainString() + ", got " + (actual == null ? "none" : actual.toPlainString()));
                }
            } else if (!v.asString().equals(rec.text(e.getKey()))) {
                diff.add(e.getKey() + ": expected " + v.asString() + ", got " + rec.text(e.getKey()));
            }
        }
        Map<String, String> gates = new LinkedHashMap<>();
        if (expect.has("gates")) {
            for (Map.Entry<String, JsonNode> e : expect.get("gates").properties()) {
                gates.put(e.getKey(), e.getValue().asString());
            }
        }
        for (Map.Entry<String, String> g : gates.entrySet()) {
            DeviceCodes.GateOutcome o = rec.outcome(g.getKey());
            if (o == null || !o.name().equals(g.getValue())) {
                diff.add("gate " + g.getKey() + ": expected " + g.getValue() + ", got " + (o == null ? "not run" : o.name()));
            }
        }
        return diff;
    }
}
