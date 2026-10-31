package com.wellnessz.e32.driver;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.wellnessz.e32.catalogue.CatalogueSeedLoader;
import com.wellnessz.e32.catalogue.InventoryMapping;
import com.wellnessz.e32.demo.E32DemoSeeder;
import com.wellnessz.e32.support.CanonicalJson;
import com.wellnessz.e32.support.Docs;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class DriverPackageRulesTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    static List<Map<String, Object>> read(String resource) throws Exception {
        try (InputStream in = DriverPackageRulesTest.class.getClassLoader().getResourceAsStream(resource)) {
            return JSON.readValue(in, new TypeReference<List<Map<String, Object>>>() {
            });
        }
    }

    static Map<String, List<String>> modelOptions() throws Exception {
        Map<String, List<String>> m = new HashMap<>();
        for (Map<String, Object> r : read(CatalogueSeedLoader.RESOURCE)) {
            m.put(Docs.str(r.get("id")), InventoryMapping.options(Docs.strings(r.get("options"))));
        }
        return m;
    }

    static List<String> codes(DriverPackageRules.Report r) {
        return r.errors().stream().map(DriverPackageRules.Issue::code).toList();
    }

    @Test
    void everyBundledPackageIsValid() throws Exception {
        Map<String, List<String>> options = modelOptions();
        List<Map<String, Object>> packages = read(E32DemoSeeder.DRIVERS);
        assertThat(packages).hasSizeGreaterThanOrEqualTo(12);
        for (Map<String, Object> p : packages) {
            DriverPackageRules.Report r = DriverPackageRules.validate(p, options);
            assertThat(r.errors()).as(Docs.str(p.get("packageId"))).isEmpty();
            assertThat(r.sha256()).hasSize(64);
        }
    }

    @Test
    void canonicalFormSortsKeysAndExcludesTheSignature() {
        Map<String, Object> pkg = Docs.of("version", "1.0.0", "packageId", "a.b", "nested", Docs.of("z", 1, "a", 2.50),
                "signature", Docs.of("keyId", "k", "value", "v"));
        String c = DriverPackageRules.canonical(pkg);
        assertThat(c).isEqualTo("{\"nested\":{\"a\":2.5,\"z\":1},\"packageId\":\"a.b\",\"version\":\"1.0.0\"}");
        assertThat(CanonicalJson.write(List.of("é\n", true, 3.0))).isEqualTo("[\"é\\n\",true,3]");
    }

    @Test
    void schemaCoverageGatesTargetsVectorsAndSecretsAreChecked() throws Exception {
        Map<String, List<String>> options = modelOptions();
        Map<String, Object> pkg = Docs.copyMap(read(E32DemoSeeder.DRIVERS).get(0));
        pkg.put("qualityGates", List.of("CALIBRATION", "MAGIC_GATE"));
        pkg.put("options", List.of("P7"));
        pkg.put("models", List.of("MD-027", "MD-999"));
        pkg.put("testVectors", List.of(Docs.of("name", "normal", "input", "x")));
        pkg.put("protocol", Docs.of("mllpPort", 2575, "password", "hunter2", "clientSecret", "vault:device/x", "tokenUrl", "https://idp.example/token"));
        List<Object> mappings = Docs.list(Docs.copy(pkg.get("mappings")));
        Docs.map(mappings.get(0)).remove("ucum");
        pkg.put("mappings", mappings);
        DriverPackageRules.Report r = DriverPackageRules.validate(pkg, options);
        assertThat(codes(r)).contains("unknown_gate", "option_not_supported_by_model", "unknown_model", "test_vectors_incomplete", "secret_in_package",
                "ucum_missing");
        assertThat(r.errors().stream().filter(i -> "secret_in_package".equals(i.code())).map(DriverPackageRules.Issue::field))
                .containsExactly("protocol.password");
        Map<String, Object> bad = Docs.copyMap(read(E32DemoSeeder.DRIVERS).get(0));
        bad.put("version", "1.0");
        bad.put("packageId", "Bad Id");
        Map<String, Object> binding = Docs.copyMap(Docs.map(Docs.list(bad.get("bindings")).get(0)));
        binding.put("target", "E99.NOWHERE");
        binding.put("fields", Docs.of("unknownKey", "measurements.x"));
        bad.put("bindings", List.of(binding));
        assertThat(codes(DriverPackageRules.validate(bad, options))).contains("semver", "pattern", "unknown_target", "binding_field_unknown");
    }

    @Test
    void versionsCompareSemantically() {
        assertThat(DriverPackageRules.compareVersions("1.10.0", "1.9.3")).isPositive();
        assertThat(DriverPackageRules.compareVersions("1.2.0", "1.2.0")).isZero();
        assertThat(DriverPackageRules.major("2.0.1")).isEqualTo(2);
        assertThat(DriverPackageRules.isReference("devsec:abc")).isTrue();
        assertThat(DriverPackageRules.isReference("plain")).isFalse();
        assertThat(DriverPackageRules.engineRange(">=1.0 <2.0")).isTrue();
        assertThat(DriverPackageRules.engineRange(">1.2.3")).isTrue();
        assertThat(DriverPackageRules.engineRange("<2.0 >=1.0")).isFalse();
        assertThat(DriverPackageRules.engineRange("any")).isFalse();
    }
}
