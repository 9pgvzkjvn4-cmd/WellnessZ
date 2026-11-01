package com.wellnessz.e32.catalogue;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.wellnessz.e32.demo.E32DemoSeeder;
import com.wellnessz.e32.driver.DriverPackageRules;
import com.wellnessz.e32.support.Docs;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Catalogue / driver mapping (data fix of round 2): the client's inventory row MD-045 stays P1 / P4, the catalogue route P2 (GE MUSE
 * XML export) lets the driver {@code ecg.ge.muse-xml} serve the model; the P3 pairing of a package must be an option-schema value.
 */
class CatalogueRoutesTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    static Map<String, List<String>> offered() throws Exception {
        Map<String, List<String>> m = new HashMap<>();
        try (var in = CatalogueRoutesTest.class.getClassLoader().getResourceAsStream(CatalogueSeedLoader.RESOURCE)) {
            for (Map<String, Object> r : JSON.readValue(in, new TypeReference<List<Map<String, Object>>>() { })) {
                String id = Docs.str(r.get("id"));
                m.put(id, CatalogueRoutes.offered(id, InventoryMapping.options(Docs.strings(r.get("options")))));
            }
        }
        return m;
    }

    static Map<String, Object> museXml() {
        return Docs.of("schema", "wellnessz.device-driver/1", "packageId", "ecg.ge.muse-xml", "version", "1.0.0", "family", "CARDIOLOGY",
                "models", List.of("MD-045"), "adapter", "FILE_XML", "options", List.of("P2"), "engineCompatibility", ">=1.0 <2.0",
                "capabilities", Docs.of("worklistPush", "HL7_ORM", "startFromForm", true, "historicRetrieval", false),
                "protocol", Docs.of("pattern", "*.xml", "encoding", "UTF-8"),
                "parse", Docs.of("type", "XPATH", "fields", List.of(Docs.of("key", "heartRate", "selector", "/RestingECG/HR", "type", "QUANTITY", "unit", "/min"))),
                "mappings", List.of(Docs.of("key", "heartRate", "loinc", "8867-4", "display", "Heart rate", "ucum", "/min")),
                "qualityGates", List.of("PLAUSIBILITY"),
                "bindings", List.of(Docs.of("formCode", "E03.DEVICE_RESULT.ECG", "formVersion", "1", "category", "ECG", "target", "E03.RESULT",
                        "fields", Docs.of("heartRate", "measurements.heartRate"), "verifyBeforePost", true)),
                "testVectors", List.of(Docs.of("name", "normal", "input", "golden/cardiology/muse-xml/normal.xml", "expect", Docs.of("heartRate", 72)),
                        Docs.of("name", "edge", "input", "golden/cardiology/muse-xml/edge.xml", "expect", Docs.of("heartRate", 48)),
                        Docs.of("name", "malformed", "input", "golden/cardiology/muse-xml/malformed.xml", "expect", Docs.of("error", "parse_failed"))));
    }

    @Test
    void md045KeepsTheInventoryOptionsAndGainsTheMuseRoute() throws Exception {
        try (var in = CatalogueRoutesTest.class.getClassLoader().getResourceAsStream(CatalogueSeedLoader.RESOURCE)) {
            List<Map<String, Object>> rows = JSON.readValue(in, new TypeReference<List<Map<String, Object>>>() { });
            Map<String, Object> md045 = rows.stream().filter(r -> "MD-045".equals(r.get("id"))).findFirst().orElseThrow();
            assertThat(Docs.strings(md045.get("options"))).containsExactly("P1", "P4"); // client inventory unchanged
        }
        assertThat(CatalogueRoutes.routes("MD-045")).singleElement().satisfies(r -> {
            assertThat(r.option()).isEqualTo("P2");
            assertThat(r.driverPackageId()).isEqualTo("ecg.ge.muse-xml");
            assertThat(r.reason()).contains("MUSE");
        });
        assertThat(CatalogueRoutes.offered("MD-045", List.of("P1", "P4"))).containsExactly("P1", "P4", "P2");
        assertThat(CatalogueRoutes.routes("MD-046")).isEmpty();
        // with the route the MUSE XML driver is a valid package for MD-045; against the raw inventory options it is not
        assertThat(DriverPackageRules.validate(museXml(), offered()).errors()).isEmpty();
        assertThat(DriverPackageRules.validate(museXml(), Map.of("MD-045", List.of("P1", "P4"))).errors())
                .anyMatch(i -> "option_not_supported_by_model".equals(i.code()));
    }

    @Test
    void bundledPackagesUseSchemaPairingValuesOnly() throws Exception {
        try (var in = CatalogueRoutesTest.class.getClassLoader().getResourceAsStream(E32DemoSeeder.DRIVERS)) {
            List<Map<String, Object>> pkgs = JSON.readValue(in, new TypeReference<List<Map<String, Object>>>() { });
            Map<String, Object> ble = Docs.copyMap(pkgs.stream().filter(p -> "vitals.and.ua651ble-blp".equals(p.get("packageId"))).findFirst().orElseThrow());
            assertThat(DriverPackageRules.validate(ble, offered()).errors()).isEmpty();
            Docs.put(ble, "protocol.pairing", "LE_SECURE_CONNECTIONS_JUST_WORKS");
            assertThat(DriverPackageRules.validate(ble, offered()).errors()).anyMatch(i -> "protocol.pairing".equals(i.field()));
        }
    }
}
