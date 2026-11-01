package com.wellnessz.e31;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ca.uhn.fhir.context.FhirContext;

import com.wellnessz.e31.adapter.Adapters;
import com.wellnessz.e31.driver.BundledDrivers;
import com.wellnessz.e31.simulator.GoldenDatasets;
import com.wellnessz.e31.terminology.Gli2012;
import com.wellnessz.e31.terminology.OshaAgeCorrection;
import com.wellnessz.e31.terminology.Terminology;
import com.wellnessz.e31.terminology.TerminologySnapshot;

import tools.jackson.databind.json.JsonMapper;

/**
 * Shared fixtures of the E31 unit tests: the terminology snapshot is read from the V22 migration itself (so the tests prove
 * that every bundled driver is covered by the shipped reference data), the bundled packages and golden datasets.
 */
public final class TestSupport {

    private static JsonMapper json;
    private static FhirContext fhir;
    private static TerminologySnapshot terminology;
    private static Adapters adapters;

    private TestSupport() {
    }

    public static synchronized JsonMapper json() {
        if (json == null) {
            json = JsonMapper.builder().build();
        }
        return json;
    }

    public static synchronized FhirContext fhir() {
        if (fhir == null) {
            fhir = FhirContext.forR4();
        }
        return fhir;
    }

    public static synchronized Adapters adapters() {
        if (adapters == null) {
            adapters = new Adapters(fhir(), json());
        }
        return adapters;
    }

    public static BundledDrivers bundled() {
        return new BundledDrivers(json());
    }

    public static GoldenDatasets golden() {
        return new GoldenDatasets(json());
    }

    static final Pattern TERM = Pattern.compile("\\('(MDC|LOINC|LOCAL)', '([^']+)', (null|'[^']*'), (null|'[^']*'), (null|'[^']*'), '([^']*)', (null|'[^']*')\\)");
    static final Pattern CONV = Pattern.compile("\\('([^']+)', '([^']+)', ([0-9.]+), (-?[0-9.]+)\\)");
    static final Pattern GLI = Pattern.compile("\\('GLI_2012', '(FEV1|FVC|FEV1FVC)',\\s+'([MF])',\\s+(-?[0-9.]+),\\s+(-?[0-9.]+),\\s+(-?[0-9.]+),\\s+(-?[0-9.]+),\\s+(-?[0-9.]+),\\s+(-?[0-9.]+),\\s+(-?[0-9.]+),");
    static final Pattern SPLINE = Pattern.compile("\\('GLI_2012', '(FEV1|FVC|FEV1FVC)', '([MF])', (\\d+), (-?[0-9.]+), (-?[0-9.]+)\\)");
    static final Pattern OSHA = Pattern.compile("\\('([MF])',(\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)\\)");
    static final Pattern RANGE = Pattern.compile("\\('([0-9]+-[0-9])', '\\*', (\\d+), (\\d+), (null|[0-9.]+), (null|[0-9.]+), '([^']*)'\\)");

    /** Terminology snapshot parsed from {@code V22__e31_device_engine.sql}. */
    public static synchronized Terminology terminology() {
        if (terminology != null) {
            return terminology;
        }
        String sql;
        try {
            sql = Files.readString(Path.of("../../apps/wz-core-app/src/main/resources/db/migration/V22__e31_device_engine.sql"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("V22 migration not found", e);
        }
        List<Terminology.Entry> entries = new ArrayList<>();
        Matcher m = TERM.matcher(sql);
        while (m.find()) {
            entries.add(new Terminology.Entry(m.group(1), m.group(2), unq(m.group(3)), unq(m.group(4)), unq(m.group(5)), m.group(6), unq(m.group(7))));
        }
        Map<String, BigDecimal[]> conv = new HashMap<>();
        String convBlock = sql.substring(sql.indexOf("insert into deviceint.unit_conversion"), sql.indexOf("insert into deviceint.reference_range"));
        Matcher c = CONV.matcher(convBlock);
        while (c.find()) {
            conv.put(TerminologySnapshot.conversionKey(c.group(1), c.group(2)), new BigDecimal[] {new BigDecimal(c.group(3)), new BigDecimal(c.group(4))});
        }
        List<TerminologySnapshot.RangeRow> ranges = new ArrayList<>();
        Matcher r = RANGE.matcher(sql);
        while (r.find()) {
            ranges.add(new TerminologySnapshot.RangeRow(r.group(1), "*", Integer.parseInt(r.group(2)), Integer.parseInt(r.group(3)), dec(r.group(4)),
                    dec(r.group(5)), r.group(6)));
        }
        Map<String, List<Gli2012.SplinePoint>> splines = new HashMap<>();
        Matcher sp = SPLINE.matcher(sql);
        while (sp.find()) {
            splines.computeIfAbsent(sp.group(1) + "|" + sp.group(2), k -> new ArrayList<>())
                    .add(new Gli2012.SplinePoint(d(sp.group(3)), d(sp.group(4)), d(sp.group(5))));
        }
        List<Gli2012.Coefficients> gli = new ArrayList<>();
        Matcher g = GLI.matcher(sql);
        while (g.find()) {
            gli.add(new Gli2012.Coefficients(g.group(1), g.group(2), d(g.group(3)), d(g.group(4)), d(g.group(5)), d(g.group(6)), d(g.group(7)),
                    d(g.group(8)), d(g.group(9)), splines.getOrDefault(g.group(1) + "|" + g.group(2), List.of())));
        }
        List<OshaAgeCorrection.Row> osha = new ArrayList<>();
        Matcher o = OSHA.matcher(sql);
        while (o.find()) {
            osha.add(new OshaAgeCorrection.Row(o.group(1), i(o.group(2)), i(o.group(3)), i(o.group(4)), i(o.group(5)), i(o.group(6)), i(o.group(7))));
        }
        terminology = new TerminologySnapshot("WZ-DEV-2026.10", entries, conv, ranges, gli, osha);
        return terminology;
    }

    static String unq(String s) {
        return "null".equals(s) ? null : s.substring(1, s.length() - 1);
    }

    static BigDecimal dec(String s) {
        return "null".equals(s) ? null : new BigDecimal(s);
    }

    static double d(String s) {
        return Double.parseDouble(s);
    }

    static int i(String s) {
        return Integer.parseInt(s);
    }
}
