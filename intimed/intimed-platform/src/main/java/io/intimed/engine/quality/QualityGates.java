package io.intimed.engine.quality;

import io.intimed.common.Json;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Quality-gate rules (IM-E01-F06 / IM-E01-BL-022, XT-14): calibration / QC-lot lockout, plausibility ranges, artefact
 * detection on continuous feeds, late / clock-skew handling, guideline grading hooks (ATS/ERS spirometry repeatability,
 * audiometric reliability, NIBP consistency, SpO2 perfusion), verify-before-post and alarm-state flagging (alarms are
 * flagged, never interpreted – IM-E01-HLR-14 / MDDS boundary).
 */
@Component
public class QualityGates {
    public static final Duration LATE = Duration.ofHours(24);
    public static final Duration SKEW = Duration.ofMinutes(5);
    private final Map<String, BigDecimal> last = new ConcurrentHashMap<>();

    public record Outcome(String hold, ObjectNode quality) { }

    public Outcome evaluate(JsonNode config, ObjectNode obs, Map<String, String> extras, Instant received) {
        ObjectNode q = Json.obj();
        ArrayNode flags = q.putArray("flags");
        String hold = null;
        JsonNode gates = config.path("qualityGates");
        // calibration lockout
        JsonNode cal = config.path("calibration");
        boolean calValid = true;
        if (cal.has("dueDate") && LocalDate.parse(cal.get("dueDate").asString()).isBefore(LocalDate.now())) {
            calValid = false;
            if (gates.path("calibrationLockout").asBoolean(true)) hold = "CALIBRATION_LOCKOUT";
            flags.add("CALIBRATION_OVERDUE");
        }
        if ("LOCKED".equals(cal.path("status").asString())) { calValid = false; hold = "CALIBRATION_LOCKOUT"; }
        q.put("calibrationValid", calValid);
        // QC lot gate (POCT)
        String lot = extras.get("lot");
        if (lot != null && gates.path("qcLotGate").asBoolean(false)) {
            JsonNode l = config.path("qc").path("lots").path(lot);
            if (!l.path("qcPassed").asBoolean(false)) { hold = hold == null ? "QC_LOT_PENDING" : hold; flags.add("QC_LOT_NOT_RELEASED"); }
            q.put("lot", lot);
        }
        // timing
        Instant eff = Instant.parse(obs.get("effectiveTime").asString());
        if (eff.isAfter(received.plus(SKEW))) {
            long off = Duration.between(received, eff).toMillis();
            obs.put("clockOffsetMs", off);
            obs.put("effectiveTime", received.toString());
            flags.add("CLOCK_SKEW_CORRECTED");
        } else if (eff.isBefore(received.minus(LATE))) {
            flags.add("LATE");
        }
        // per measurement
        String devKey = config.path("id").asString() + "/" + obs.path("deviceSerial").asString("");
        BigDecimal sys = null, dia = null;
        for (JsonNode m : obs.get("measurements")) {
            ObjectNode mm = (ObjectNode) m;
            ArrayNode mf = mm.putArray("flags");
            if (!mm.get("value").isNumber()) continue;
            BigDecimal v = mm.get("value").decimalValue();
            JsonNode pl = mm.path("plausible");
            if (pl.has("min") && v.compareTo(pl.get("min").decimalValue()) < 0 || pl.has("max") && v.compareTo(pl.get("max").decimalValue()) > 0) {
                mf.add("OUT_OF_PLAUSIBLE_RANGE");
                flags.add("OUT_OF_PLAUSIBLE_RANGE:" + mm.get("key").asString());
                if (gates.path("blockImplausible").asBoolean(true)) hold = hold == null ? "VERIFY" : hold;
            }
            if (mm.has("artefactJumpPct") && "CONTINUOUS".equals(config.path("captureMode").asString())) {
                BigDecimal prev = last.put(devKey + "/" + mm.get("key").asString(), v);
                if (prev != null && prev.signum() != 0) {
                    double jump = v.subtract(prev).abs().doubleValue() / prev.abs().doubleValue() * 100;
                    if (jump > mm.get("artefactJumpPct").asDouble()) { mf.add("ARTEFACT_SUSPECTED"); flags.add("ARTEFACT:" + mm.get("key").asString()); }
                }
            }
            String key = mm.get("key").asString();
            if (key.equals("systolic")) sys = v;
            if (key.equals("diastolic")) dia = v;
            if (key.equals("pi") && v.compareTo(new BigDecimal("0.4")) < 0) flags.add("LOW_PERFUSION");
        }
        if (sys != null && dia != null && sys.compareTo(dia) <= 0) { flags.add("NIBP_INCONSISTENT"); hold = hold == null ? "VERIFY" : hold; }
        grade(config, obs, q);
        if (extras.containsKey("alarm")) { flags.add("ALARM_STATE_FLAGGED"); q.put("alarm", extras.get("alarm")); }
        if ("true".equals(extras.get("verifyRequired")) || gates.path("verifyBeforePost").asBoolean(false)) {
            q.put("verifyRequired", true);
            hold = hold == null ? "VERIFY" : hold;
        }
        return new Outcome(hold, q);
    }

    /** Guideline grading hooks selected by the driver semantics / configuration (RR-11). */
    void grade(JsonNode config, ObjectNode obs, ObjectNode q) {
        String g = config.path("qualityGates").path("grading").asString("");
        Map<String, BigDecimal> v = new java.util.HashMap<>();
        obs.get("measurements").forEach(m -> { if (m.get("value").isNumber()) v.put(m.get("key").asString(), m.get("value").decimalValue()); });
        switch (g) {
            case "ATS_ERS_2019" -> {
                BigDecimal rep = v.containsKey("repeatability") ? v.get("repeatability") : v.get("fvcRepeatability");
                if (rep != null) {
                    double ml = rep.doubleValue();
                    q.put("grade", ml <= 150 ? "A" : ml <= 200 ? "B" : ml <= 250 ? "C" : ml <= 350 ? "D" : "E");
                    q.put("gradingRule", "ATS/ERS 2019 FVC repeatability (mL)");
                }
                if (v.containsKey("fev1") && v.containsKey("fvc") && v.get("fvc").signum() > 0) {
                    q.put("fev1FvcRatio", v.get("fev1").divide(v.get("fvc"), 3, java.math.RoundingMode.HALF_UP));
                }
            }
            case "AUDIOMETRIC_VALIDITY" -> {
                BigDecimal r = v.get("reliability");
                q.put("grade", r != null && r.doubleValue() >= 1 ? "VALID" : "RETEST");
                q.put("gradingRule", "ISO 8253-1 response reliability");
            }
            case "BP_VALIDATION" -> q.put("gradingRule", "ESH/AAMI validated device; systolic > diastolic");
            case "SPO2_PERFUSION" -> q.put("gradingRule", "perfusion index ≥ 0.4 %");
            default -> { }
        }
    }

    public void resetHistory() { last.clear(); }
}
