package com.wellnessz.e31.pipeline;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceObservation;
import com.wellnessz.e31.adapter.ParsedRecord;
import com.wellnessz.e31.driver.DriverPackage;
import com.wellnessz.e31.support.EngineException;
import com.wellnessz.e31.terminology.Gli2012;
import com.wellnessz.e31.terminology.OshaAgeCorrection;
import com.wellnessz.e31.terminology.Terminology;

/**
 * NORMALISE stage (E31-HLR-04, E31-F04): driver mappings → canonical components with MDC + LOINC + UCUM, unit conversion
 * (driver {@code convert}, else the UCUM conversion table), rounding, reference-range interpretation, GLI-2012 predicted
 * values and LLN for spirometry, BMI from weight and height, the OSHA Appendix F age-correction row for audiometry, and
 * clock-skew correction of the effective time. Deterministic for a given driver version and terminology release.
 */
public final class Normaliser {

    private Normaliser() {
    }

    /**
     * @param edgeClock      edge clock at send ({@code X-Edge-Clock}), {@code null} when unknown
     * @param capturedAt     edge receipt time by the edge clock ({@code X-Captured-At})
     * @param maxSkewSeconds {@code device.clock.max-skew-seconds}
     */
    public record Context(Instant receivedAt, Instant capturedAt, Instant edgeClock, long maxSkewSeconds) {
    }

    public record Demographics(String sex, Integer ageYears, BigDecimal heightCm, LocalDate dateOfBirth, String name) {
    }

    /**
     * @param derived values computed for the bindings (ageYears, sex, ageCorrection)
     */
    public record Result(List<DeviceObservation.Component> components, Instant effectiveAt, Instant deviceTime, long clockSkewMillis,
                         Demographics demographics, Map<String, Object> derived) {

        public Optional<DeviceObservation.Component> component(String key) {
            return components.stream().filter(c -> c.key().equals(key)).findFirst();
        }
    }

    public static Result normalise(DriverPackage pkg, ParsedRecord record, Terminology terminology, Context ctx) {
        Timing timing = timing(record.identity().deviceTime(), ctx);
        long skew = timing.skewMillis();
        Instant effective = timing.effectiveAt();
        Demographics demo = demographics(record.identity(), effective);
        List<DeviceObservation.Component> components = new ArrayList<>();
        for (ParsedRecord.Value v : record.values().values()) {
            DriverPackage.Mapping m = pkg.mapping(v.key()).orElse(null);
            if (m == null) {
                if ("QUANTITY".equals(v.type())) {
                    throw new EngineException("mapping_missing", "No mapping for field " + v.key(), true);
                }
                continue; // an unmapped flag / code is transport detail (e.g. a unit flag), never a coded component
            }
            components.add(component(pkg, m, v, terminology, demo));
        }
        Map<String, Object> derived = new LinkedHashMap<>();
        if (demo.ageYears() != null) {
            derived.put("ageYears", demo.ageYears());
        }
        if (demo.sex() != null) {
            derived.put("sex", demo.sex());
        }
        if (pkg.predicted() != null && "GLI_2012".equals(pkg.predicted().equation())) {
            components.addAll(predicted(pkg.predicted(), components, demo, terminology));
        }
        bmi(components);
        if (pkg.family() == DeviceCodes.Family.AUDIOMETRY && demo.ageYears() != null && demo.sex() != null) {
            terminology.oshaRow(demo.sex(), demo.ageYears()).ifPresent(r -> derived.put("ageCorrection", r.asMap()));
        }
        return new Result(List.copyOf(components), effective, record.identity().deviceTime(), skew, demo, derived);
    }

    /** Effective time after clock-skew correction and the total skew (device / edge clock minus engine clock). */
    public record Timing(Instant effectiveAt, long skewMillis) {
    }

    /**
     * Clock-skew correction: the edge skew ({@code X-Edge-Clock} − server receipt) is removed from every edge / device time; a
     * device time still in the future beyond the tolerance is a device clock error – the effective time falls back to the
     * (corrected) capture time and the difference is added to the skew. Older device times are legitimate (vendor exports).
     */
    public static Timing timing(Instant deviceTime, Context ctx) {
        long edgeSkew = ctx.edgeClock() == null ? 0 : Duration.between(ctx.receivedAt(), ctx.edgeClock()).toMillis();
        Instant reference = ctx.capturedAt() == null ? ctx.receivedAt() : ctx.capturedAt().minusMillis(edgeSkew);
        if (reference.isAfter(ctx.receivedAt())) {
            reference = ctx.receivedAt();
        }
        Instant device = deviceTime == null ? reference : deviceTime.minusMillis(edgeSkew);
        if (device.isAfter(reference.plusSeconds(ctx.maxSkewSeconds()))) {
            return new Timing(reference, edgeSkew + Duration.between(reference, device).toMillis());
        }
        return new Timing(device, edgeSkew);
    }

    static Demographics demographics(ParsedRecord.Identity id, Instant effective) {
        Integer age = id.ageYears();
        if (age == null && id.dateOfBirth() != null) {
            age = Period.between(id.dateOfBirth(), LocalDate.ofInstant(effective, ZoneOffset.UTC)).getYears();
        }
        return new Demographics(id.sex(), age, id.heightCm(), id.dateOfBirth(), id.name());
    }

    static DeviceObservation.Component component(DriverPackage pkg, DriverPackage.Mapping m, ParsedRecord.Value v, Terminology terminology,
                                                 Demographics demo) {
        String mdc = m.mdc() != null ? m.mdc() : terminology.byLoinc(m.loinc()).map(Terminology.Entry::mdc).orElse(null);
        if (!"QUANTITY".equals(v.type())) {
            return new DeviceObservation.Component(m.key(), mdc, m.loinc(), m.display(), v.type(), null, v.text(), null, m.bodySite(), m.method(), null);
        }
        BigDecimal value = v.number();
        String reported = v.unit();
        String target = m.ucum();
        if (target != null && reported != null && !reported.equals(target)) {
            if (m.convert() != null && reported.equals(m.convert().fromUnit())) {
                BigDecimal offset = m.convert().offset() == null ? BigDecimal.ZERO : m.convert().offset();
                value = value.multiply(m.convert().factor(), MathContext.DECIMAL64).add(offset, MathContext.DECIMAL64);
            } else {
                BigDecimal converted = terminology.convert(value, reported, target).orElse(null);
                if (converted == null) {
                    throw new EngineException("unit_unconvertible", "Unit of " + m.key() + " cannot be converted to " + target, true);
                }
                value = converted;
            }
        }
        if (m.decimals() != null) {
            value = value.setScale(m.decimals(), RoundingMode.HALF_UP);
        } else if (value.scale() > 6) {
            value = value.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
        }
        String interpretation = interpretation(pkg, m, value, terminology, demo);
        return new DeviceObservation.Component(m.key(), mdc, m.loinc(), m.display(), "QUANTITY", value, null, target == null ? reported : target,
                m.bodySite(), m.method(), interpretation);
    }

    static String interpretation(DriverPackage pkg, DriverPackage.Mapping m, BigDecimal value, Terminology terminology, Demographics demo) {
        for (DriverPackage.RefRange r : pkg.referenceRanges()) {
            if (!m.key().equals(r.key()) || !("*".equals(r.sex()) || r.sex().equals(demo.sex()))) {
                continue;
            }
            if (demo.ageYears() != null && (demo.ageYears() < r.ageMin() || demo.ageYears() > r.ageMax())) {
                continue;
            }
            return new Terminology.Range(r.low(), r.high(), r.text()).interpret(value);
        }
        return terminology.range(m.loinc(), demo.sex(), demo.ageYears()).map(r -> r.interpret(value)).orElse(null);
    }

    static List<DeviceObservation.Component> predicted(DriverPackage.Predicted predicted, List<DeviceObservation.Component> components,
                                                       Demographics demo, Terminology terminology) {
        List<DeviceObservation.Component> out = new ArrayList<>();
        if (demo.sex() == null || demo.ageYears() == null || demo.heightCm() == null) {
            return out;
        }
        for (Map.Entry<String, String> e : predicted.keys().entrySet()) {
            DeviceObservation.Component observed = components.stream().filter(c -> c.key().equals(e.getKey()) && c.value() != null)
                    .findFirst().orElse(null);
            Gli2012.Coefficients c = terminology.gli(e.getValue(), demo.sex()).orElse(null);
            if (c == null) {
                continue;
            }
            Gli2012.Prediction p;
            try {
                p = Gli2012.predict(c, demo.ageYears(), demo.heightCm().doubleValue());
            } catch (IllegalArgumentException outOfRange) {
                continue;
            }
            switch (e.getValue()) {
                case "FEV1" -> {
                    out.add(quantity("fev1PredictedL", null, "20149-1", "FEV1 predicted (GLI-2012)", p.predicted(), "L", 2));
                    out.add(quantity("fev1LlnL", null, "LOCAL:FEV1_LLN", "FEV1 lower limit of normal (GLI-2012)", p.lln(), "L", 2));
                    if (observed != null) {
                        out.add(quantity("fev1PctPredicted", null, "LOCAL:FEV1_PCT_PRED", "FEV1 % predicted",
                                p.percentPredicted(observed.value().doubleValue()), "%", 1));
                        out.add(quantity("fev1ZScore", null, "LOCAL:FEV1_Z", "FEV1 z-score", p.zScore(observed.value().doubleValue()), "1", 2));
                    }
                }
                case "FVC" -> {
                    out.add(quantity("fvcPredictedL", null, "19869-7", "FVC predicted (GLI-2012)", p.predicted(), "L", 2));
                    out.add(quantity("fvcLlnL", null, "LOCAL:FVC_LLN", "FVC lower limit of normal (GLI-2012)", p.lln(), "L", 2));
                    if (observed != null) {
                        out.add(quantity("fvcPctPredicted", null, "LOCAL:FVC_PCT_PRED", "FVC % predicted",
                                p.percentPredicted(observed.value().doubleValue()), "%", 1));
                    }
                }
                case "FEV1FVC" -> out.add(quantity("ratioLln", null, "LOCAL:RATIO_LLN", "FEV1/FVC lower limit of normal (GLI-2012)",
                        p.lln() * 100.0, "%", 1));
                default -> {
                }
            }
        }
        return out;
    }

    static void bmi(List<DeviceObservation.Component> components) {
        DeviceObservation.Component w = components.stream().filter(c -> "weightKg".equals(c.key()) && c.value() != null).findFirst().orElse(null);
        DeviceObservation.Component h = components.stream().filter(c -> "heightCm".equals(c.key()) && c.value() != null).findFirst().orElse(null);
        boolean hasBmi = components.stream().anyMatch(c -> "bmi".equals(c.key()));
        if (w != null && h != null && !hasBmi && h.value().signum() > 0) {
            double m = h.value().doubleValue() / 100.0;
            components.add(quantity("bmi", "MDC_RATIO_MASS_BODY_LEN_SQ", "39156-5", "Body mass index (computed)",
                    w.value().doubleValue() / (m * m), "kg/m2", 1));
        }
    }

    static DeviceObservation.Component quantity(String key, String mdc, String loinc, String display, double value, String ucum, int decimals) {
        return new DeviceObservation.Component(key, mdc, loinc, display, "QUANTITY", BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP),
                null, ucum, null, null, null);
    }
}
