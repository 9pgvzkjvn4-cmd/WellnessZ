package com.wellnessz.e31.driver;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.wellnessz.e31.TestSupport;
import com.wellnessz.e31.gate.GateEnvironment;
import com.wellnessz.e31.simulator.DryRun;
import com.wellnessz.e31.simulator.GoldenDatasets;

import tools.jackson.databind.JsonNode;

/**
 * The engine runs the packages the E32 registry publishes (fixture: a copy of the E32 demo registry, 12 packages): each one
 * parses, and the engine's NORMAL golden dataset of the same package id goes through PARSE → NORMALISE → VALIDATE without a
 * pipeline error and with LOINC / UCUM on every component (contract-style manifests: unit flags, unmapped transport flags).
 */
class RegistryPackagesCompatibilityTest {

    @Test
    void registryManifestsRunAgainstTheGoldenDatasets() throws IOException {
        JsonNode root;
        try (InputStream in = getClass().getResourceAsStream("/e31-test/e32-demo-drivers.json")) {
            root = TestSupport.json().readTree(in);
        }
        List<JsonNode> packages = new ArrayList<>();
        (root.isArray() ? root : root.path("packages")).forEach(packages::add);
        assertThat(packages).hasSize(12);
        DriverParser parser = new DriverParser(TestSupport.json());
        GoldenDatasets golden = TestSupport.golden();
        int ran = 0;
        for (JsonNode n : packages) {
            DriverPackage p = parser.parse(TestSupport.json().writeValueAsString(n));
            GoldenDatasets.Dataset d = golden.list(null).stream().filter(x -> x.packageId().equals(p.packageId()) && "NORMAL".equals(x.kind()))
                    .findFirst().orElse(null);
            if (d == null) {
                continue;
            }
            DryRun.Result r = DryRun.run(TestSupport.adapters(), TestSupport.terminology(), p, golden.content(d.resource()), d.contentType(), d.headers(),
                    Instant.parse("2026-10-30T12:00:00Z"), null, GateEnvironment.SIMULATOR, 120);
            assertThat(r.error()).as(p.ref() + " " + r.errorMessage()).isNull();
            assertThat(r.records().get(0).components()).as(p.ref()).isNotEmpty()
                    .allMatch(c -> c.loincCode() != null && (!"QUANTITY".equals(c.valueType()) || c.ucum() != null));
            ran++;
        }
        assertThat(ran).isEqualTo(12);
    }
}
