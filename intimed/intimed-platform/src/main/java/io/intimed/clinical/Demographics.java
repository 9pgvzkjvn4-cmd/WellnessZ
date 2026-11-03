package io.intimed.clinical;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Partitioning factors for reference intervals (CLSI EP28-A3c recommends age and sex partitioning; the knowledge base adds
 * pregnancy/trimester, fasting state, site altitude, occupational regulation and respirator type).
 */
public record Demographics(Double ageYears, String sex, boolean pregnant, Integer trimester, boolean fasting, Integer siteAltitudeM,
                           String regulation, String respiratorType) {

    public static final Demographics UNKNOWN = new Demographics(null, null, false, null, false, null, null, null);

    public static Demographics of(JsonNode j) {
        if (j == null || j.isMissingNode() || j.isNull()) return UNKNOWN;
        return new Demographics(j.hasNonNull("ageYears") ? j.get("ageYears").asDouble() : null, j.path("sex").asString(null),
                j.path("pregnant").asBoolean(false), j.hasNonNull("trimester") ? j.get("trimester").asInt() : null, j.path("fasting").asBoolean(false),
                j.hasNonNull("siteAltitudeM") ? j.get("siteAltitudeM").asInt() : null, j.path("regulation").asString(null), j.path("respiratorType").asString(null));
    }

    /** From a subject record (birthDate, sex, pregnant, trimester) at the observation's effective time. */
    public static Demographics fromSubject(JsonNode s, Instant at, JsonNode context) {
        if (s == null) return UNKNOWN;
        Double age = null;
        if (s.hasNonNull("birthDate")) {
            LocalDate b = LocalDate.parse(s.get("birthDate").asString());
            age = ChronoUnit.DAYS.between(b, at.atZone(ZoneOffset.UTC).toLocalDate()) / 365.25;
        }
        JsonNode c = context == null ? s : context;
        return new Demographics(age, s.path("sex").asString(null), s.path("pregnant").asBoolean(false), s.hasNonNull("trimester") ? s.get("trimester").asInt() : null,
                c.path("fasting").asBoolean(false), c.hasNonNull("siteAltitudeM") ? c.get("siteAltitudeM").asInt() : null, c.path("regulation").asString(null), c.path("respiratorType").asString(null));
    }

    public Demographics withContext(JsonNode params) {
        if (params == null) return this;
        return new Demographics(ageYears, sex, pregnant, trimester, fasting || params.path("fasting").asBoolean(false),
                siteAltitudeM != null ? siteAltitudeM : params.hasNonNull("siteAltitudeM") ? params.get("siteAltitudeM").asInt() : null,
                regulation != null ? regulation : params.path("regulation").asString(null), respiratorType != null ? respiratorType : params.path("respiratorType").asString(null));
    }

    public String describe() {
        StringBuilder b = new StringBuilder();
        b.append(ageYears == null ? "age unknown" : ageYears < 1 ? Math.round(ageYears * 365) + " days" : String.format("%.0f y", ageYears));
        b.append(", ").append(sex == null ? "sex unknown" : sex);
        if (pregnant) b.append(", pregnant").append(trimester == null ? "" : " T" + trimester);
        if (fasting) b.append(", fasting");
        if (siteAltitudeM != null && siteAltitudeM > 0) b.append(", site ").append(siteAltitudeM).append(" m");
        if (regulation != null) b.append(", ").append(regulation);
        return b.toString();
    }
}
