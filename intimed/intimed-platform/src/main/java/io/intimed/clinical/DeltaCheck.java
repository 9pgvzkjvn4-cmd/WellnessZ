package io.intimed.clinical;

import io.intimed.common.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;

/**
 * Delta checks (IM-E14-BL-012): compares a result with the subject's previous filed result of the same concept within a
 * window and flags {@code delta} when |Δ| exceeds the knowledge-base threshold – absolute ({@code delta.abs}, KB unit) or
 * relative ({@code delta.pct}, % of the previous value). Informational only: the host owns any call-back or
 * acknowledgement workflow; IntiMed never raises an alarm.
 */
public final class DeltaCheck {
    private DeltaCheck() { }

    public record Result(boolean flagged, BigDecimal change, Double pct, String threshold, long minutesSincePrevious) {
        public ObjectNode toJson(BigDecimal previous, Instant previousAt, int windowHours) {
            ObjectNode o = Json.obj().put("flagged", flagged).put("previous", previous).put("previousAt", previousAt.toString()).put("change", change)
                    .put("threshold", threshold).put("windowHours", windowHours).put("minutesSincePrevious", minutesSincePrevious)
                    .put("note", "delta check – informational; the host owns call-back / acknowledgement");
            if (pct != null) o.put("changePct", Math.round(pct * 10) / 10.0);
            return o;
        }
    }

    /** Window in hours: configuration override ({@code params.deltaWindowHours}) → KB entry → 72 h. */
    public static int windowHours(JsonNode entry, JsonNode config) {
        int cfg = config == null ? 0 : config.path("params").path("deltaWindowHours").asInt(0);
        return cfg > 0 ? cfg : entry.path("delta").path("windowHours").asInt(72);
    }

    /** Null when the entry defines no delta threshold or the previous value is outside the window. */
    public static Result check(JsonNode entry, BigDecimal previous, Instant previousAt, BigDecimal value, Instant at, int windowHours) {
        JsonNode d = entry == null ? null : entry.get("delta");
        if (d == null || previous == null || previousAt == null || value == null) return null;
        long minutes = Duration.between(previousAt, at).toMinutes();
        if (minutes < 0 || minutes > windowHours * 60L) return null;
        BigDecimal change = value.subtract(previous);
        Double pct = previous.signum() == 0 ? null : change.abs().doubleValue() / previous.abs().doubleValue() * 100;
        boolean flag = false;
        StringBuilder th = new StringBuilder();
        if (d.has("abs")) {
            flag |= change.abs().compareTo(d.get("abs").decimalValue()) > 0;
            th.append("|Δ| > ").append(d.get("abs").decimalValue().stripTrailingZeros().toPlainString()).append(' ').append(entry.path("unit").asString());
        }
        if (d.has("pct")) {
            flag |= pct != null && pct > d.get("pct").asDouble();
            if (!th.isEmpty()) th.append(" or ");
            th.append("|Δ| > ").append(d.get("pct").asDouble()).append(" %");
        }
        return new Result(flag, change.setScale(Math.max(value.scale(), previous.scale()), RoundingMode.HALF_UP), pct, th.toString(), minutes);
    }

    /** Default ON only when reference-range flagging is ON; {@code params.deltaChecks} overrides. */
    public static boolean enabled(JsonNode config) {
        JsonNode p = config == null ? null : config.path("params");
        boolean flagging = p == null || p.path("referenceRangeFlagging").asBoolean(true);
        if (p != null && p.has("deltaChecks")) return p.get("deltaChecks").asBoolean() && flagging;
        return flagging;
    }
}
