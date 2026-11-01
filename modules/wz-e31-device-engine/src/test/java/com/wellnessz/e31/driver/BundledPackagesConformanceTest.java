package com.wellnessz.e31.driver;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.wellnessz.e31.TestSupport;
import com.wellnessz.e31.gate.GateEnvironment;
import com.wellnessz.e31.simulator.DryRun;
import com.wellnessz.e31.simulator.GoldenDatasets;

/**
 * E31-HLR-10 / E31-F10 in CI: every sample driver package is schema-valid, fully mapped (LOINC + UCUM, codes in the shipped
 * terminology release), and passes all of its test vectors in the simulator; every package has the five golden dataset
 * kinds. A failing package blocks the build.
 */
class BundledPackagesConformanceTest {

    static final Set<String> TARGETS = Set.of("E03.RESULT", "E07.IH_MEASUREMENT", "E10.BREATH_ALCOHOL", "E12.SCREENING", "E12.WEARABLE");

    @Test
    void everySamplePackageValidatesWithFullCoverage() {
        BundledDrivers bundled = TestSupport.bundled();
        assertThat(bundled.all()).hasSize(18);
        for (DriverPackage p : bundled.all()) {
            DriverValidator.Report r = DriverValidator.validate(p, TestSupport.terminology(), TARGETS, List.of());
            assertThat(r.errors()).as(p.ref()).isEmpty();
            assertThat(r.coverage().mapped()).as(p.ref()).isEqualTo(r.coverage().fields());
            assertThat(r.coverage().coded()).as(p.ref()).isEqualTo(r.coverage().fields());
            assertThat(r.coverage().quantitiesWithUcum()).as(p.ref()).isEqualTo(r.coverage().quantities());
            assertThat(r.warnings()).as(p.ref()).noneMatch(w -> w.contains("not deployed"));
        }
    }

    @Test
    void everyTestVectorPassesInTheSimulator() {
        GoldenDatasets golden = TestSupport.golden();
        for (DriverPackage p : TestSupport.bundled().all()) {
            assertThat(p.testVectors()).as(p.ref()).hasSizeGreaterThanOrEqualTo(3);
            for (DriverPackage.TestVector v : p.testVectors()) {
                DryRun.Result r = DryRun.run(TestSupport.adapters(), TestSupport.terminology(), p, golden.content(v.input()), null,
                        Map.of("X-File-Name", "x"), Instant.parse("2026-10-30T12:00:00Z"), null, GateEnvironment.SIMULATOR, 120);
                assertThat(DryRun.diff(r, v.expect())).as(p.ref() + " " + v.name() + " " + r.errorMessage()).isEmpty();
            }
        }
    }

    @Test
    void everyPackageHasTheFiveGoldenDatasetKinds() {
        Map<String, Set<String>> kinds = TestSupport.golden().list(null).stream()
                .collect(Collectors.groupingBy(GoldenDatasets.Dataset::packageId, Collectors.mapping(GoldenDatasets.Dataset::kind, Collectors.toSet())));
        for (DriverPackage p : TestSupport.bundled().all()) {
            assertThat(kinds.get(p.packageId())).as(p.packageId()).contains("NORMAL", "EDGE", "MALFORMED", "TIME_SKEW", "DUPLICATE");
        }
        assertThat(TestSupport.golden().list(null)).anyMatch(d -> "PERFORMANCE".equals(d.kind()));
    }

    @Test
    void goldenDatasetsParseAsDeclared() {
        GoldenDatasets golden = TestSupport.golden();
        BundledDrivers bundled = TestSupport.bundled();
        for (GoldenDatasets.Dataset d : golden.list(null)) {
            if ("PERFORMANCE".equals(d.kind())) {
                continue;
            }
            DriverPackage p = bundled.get(d.packageId(), null).orElseThrow();
            DryRun.Result r = DryRun.run(TestSupport.adapters(), TestSupport.terminology(), p, golden.content(d.resource()), d.contentType(), d.headers(),
                    Instant.parse("2026-10-30T12:00:00Z"), null, GateEnvironment.SIMULATOR, 120);
            if ("MALFORMED".equals(d.kind())) {
                assertThat(r.error()).as(d.datasetId()).isIn("parse_failed", "profile_violation");
            } else {
                assertThat(r.error()).as(d.datasetId() + " " + r.errorMessage()).isNull();
                assertThat(r.records()).as(d.datasetId()).isNotEmpty();
                assertThat(r.records().get(0).components()).as(d.datasetId())
                        .allMatch(c -> c.loincCode() != null && (!"QUANTITY".equals(c.valueType()) || c.ucum() != null));
            }
        }
    }
}
