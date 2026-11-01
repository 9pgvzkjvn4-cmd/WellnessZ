package com.wellnessz.e31.terminology;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Immutable in-memory snapshot of the terminology tables (loaded from {@code deviceint}, or built by tests). */
public final class TerminologySnapshot implements Terminology {

    private final String release;
    private final List<Entry> entries;
    private final Map<String, BigDecimal[]> conversions;
    private final List<RangeRow> ranges;
    private final Map<String, Gli2012.Coefficients> gli;
    private final Map<String, OshaAgeCorrection.Row> osha;

    public record RangeRow(String loinc, String sex, int ageMin, int ageMax, BigDecimal low, BigDecimal high, String text) {
    }

    public TerminologySnapshot(String release, List<Entry> entries, Map<String, BigDecimal[]> conversions, List<RangeRow> ranges,
                               List<Gli2012.Coefficients> gli, List<OshaAgeCorrection.Row> osha) {
        this.release = release;
        this.entries = List.copyOf(entries);
        this.conversions = Map.copyOf(conversions);
        this.ranges = List.copyOf(ranges);
        Map<String, Gli2012.Coefficients> g = new HashMap<>();
        for (Gli2012.Coefficients c : gli) {
            g.put(c.parameter() + "|" + c.sex(), c);
        }
        this.gli = Map.copyOf(g);
        Map<String, OshaAgeCorrection.Row> o = new HashMap<>();
        for (OshaAgeCorrection.Row r : osha) {
            o.put(r.sex() + "|" + r.age(), r);
        }
        this.osha = Map.copyOf(o);
    }

    public static String conversionKey(String from, String to) {
        return from + "->" + to;
    }

    @Override
    public String releaseVersion() {
        return release;
    }

    @Override
    public boolean known(String code) {
        if (code == null) {
            return false;
        }
        return entries.stream().anyMatch(e -> code.equals(e.code()) || code.equals(e.loinc()) || code.equals(e.mdc()));
    }

    @Override
    public Optional<Entry> byLoinc(String loinc) {
        return entries.stream().filter(e -> loinc != null && loinc.equals(e.loinc()) && !"MDC".equals(e.system())).findFirst()
                .or(() -> entries.stream().filter(e -> loinc != null && loinc.equals(e.loinc())).findFirst());
    }

    @Override
    public Optional<Entry> byMdc(String mdc) {
        return entries.stream().filter(e -> mdc != null && mdc.equals(e.mdc())).findFirst();
    }

    @Override
    public Optional<BigDecimal> convert(BigDecimal value, String fromUnit, String toUnit) {
        if (value == null || fromUnit == null || toUnit == null) {
            return Optional.empty();
        }
        if (fromUnit.equals(toUnit)) {
            return Optional.of(value);
        }
        BigDecimal[] c = conversions.get(conversionKey(fromUnit, toUnit));
        return c == null ? Optional.empty() : Optional.of(value.multiply(c[0], MathContext.DECIMAL64).add(c[1], MathContext.DECIMAL64));
    }

    @Override
    public Optional<Range> range(String loinc, String sex, Integer ageYears) {
        return ranges.stream()
                .filter(r -> r.loinc().equals(loinc))
                .filter(r -> "*".equals(r.sex()) || r.sex().equals(sex))
                .filter(r -> ageYears == null || ageYears >= r.ageMin() && ageYears <= r.ageMax())
                .findFirst().map(r -> new Range(r.low(), r.high(), r.text()));
    }

    @Override
    public Optional<Gli2012.Coefficients> gli(String parameter, String sex) {
        return Optional.ofNullable(gli.get(parameter + "|" + sex));
    }

    @Override
    public Optional<OshaAgeCorrection.Row> oshaRow(String sex, int age) {
        return Optional.ofNullable(osha.get(sex + "|" + OshaAgeCorrection.clampAge(age)));
    }

    @Override
    public List<Entry> entries(String system, String code) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if ((system == null || system.equals(e.system())) && (code == null || code.equals(e.code()) || code.equals(e.loinc())
                    || code.equals(e.mdc()))) {
                out.add(e);
            }
        }
        return out;
    }
}
