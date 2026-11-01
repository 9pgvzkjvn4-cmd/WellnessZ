package com.wellnessz.e31.driver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.wellnessz.e31.TestSupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Driver schema, canonical JSON and signatures, semantic versions and the validation report (secrets, coverage, vectors). */
class DriverPackageTest {

    final DriverParser parser = new DriverParser(TestSupport.json());

    String sample() {
        return TestSupport.bundled().source("vitals.welchallyn.csm-pcd01", null).orElseThrow();
    }

    @Test
    void canonicalJsonSortsKeysDropsWhitespaceAndTheSignature() {
        JsonNode a = TestSupport.json().readTree("{\"b\": 1.50, \"a\": {\"y\": [2, \"x\"], \"x\": true}, \"signature\": {\"value\": \"zzz\"}}");
        assertThat(CanonicalJson.of(a)).isEqualTo("{\"a\":{\"x\":true,\"y\":[2,\"x\"]},\"b\":1.5}");
        JsonNode reordered = TestSupport.json().readTree("{\"a\": {\"x\": true, \"y\": [2, \"x\"]}, \"b\": 1.5}");
        assertThat(CanonicalJson.of(reordered)).isEqualTo(CanonicalJson.of(a));
        assertThat(CanonicalJson.of(TestSupport.json().readTree("{\"s\":\"a\\\"b\\n\"}"))).isEqualTo("{\"s\":\"a\\\"b\\n\"}");
    }

    @Test
    void ed25519SignatureOverTheCanonicalManifestVerifiesAndDetectsTampering() throws Exception {
        DriverPackage p = parser.parse(sample());
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(kp.getPrivate());
        s.update(p.canonicalJson().getBytes(StandardCharsets.UTF_8));
        byte[] sig = s.sign();
        ObjectNode signed = (ObjectNode) parser.tree(sample());
        signed.putObject("signature").put("keyId", "k1").put("algorithm", "Ed25519").put("value", Base64.getEncoder().encodeToString(sig));
        DriverPackage reparsed = parser.parse(signed);
        assertThat(reparsed.canonicalJson()).isEqualTo(p.canonicalJson());   // signature member not covered
        assertThat(reparsed.signature().keyId()).isEqualTo("k1");
        Signature v = Signature.getInstance("Ed25519");
        v.initVerify(kp.getPublic());
        v.update(reparsed.canonicalJson().getBytes(StandardCharsets.UTF_8));
        assertThat(v.verify(sig)).isTrue();
        signed.put("version", "1.0.1");
        v.initVerify(kp.getPublic());
        v.update(parser.parse(signed).canonicalJson().getBytes(StandardCharsets.UTF_8));
        assertThat(v.verify(sig)).isFalse();
    }

    @Test
    void schemaViolationsAreCollected() {
        assertThatThrownBy(() -> parser.parse("{\"packageId\":\"Bad Id\",\"version\":\"1\",\"family\":\"NOPE\",\"adapter\":\"X\",\"parse\":{\"type\":\"?\"}}"))
                .isInstanceOf(DriverException.class)
                .satisfies(e -> assertThat(((DriverException) e).errors()).anyMatch(x -> x.startsWith("packageId")).anyMatch(x -> x.startsWith("version"))
                        .anyMatch(x -> x.startsWith("family")).anyMatch(x -> x.startsWith("adapter")).anyMatch(x -> x.startsWith("models"))
                        .anyMatch(x -> x.startsWith("parse.type")).anyMatch(x -> x.startsWith("mappings")));
        assertThatThrownBy(() -> parser.parse("not json")).isInstanceOf(DriverException.class);
    }

    @Test
    void semanticVersionsAndEngineRange() {
        assertThat(SemVer.satisfies("1.0.0", ">=1.0 <2.0")).isTrue();
        assertThat(SemVer.satisfies("2.0.0", ">=1.0 <2.0")).isFalse();
        assertThat(SemVer.satisfies("1.2.3", "1.2.3")).isTrue();
        assertThat(SemVer.parse("1.10.0")).isGreaterThan(SemVer.parse("1.9.9"));
        assertThat(SemVer.isStrict("1.0")).isFalse();
        assertThatThrownBy(() -> SemVer.satisfies("1.0.0", "~1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatorFindsSecretsUnmappedFieldsUnknownGatesAndMissingVectors() {
        ObjectNode bad = (ObjectNode) parser.tree(sample());
        ((ObjectNode) bad.get("protocol")).put("password", "hunter2");
        ((ObjectNode) bad.get("protocol")).put("secretRef", "devsec:3f2a");
        ((tools.jackson.databind.node.ArrayNode) bad.get("mappings")).remove(0);
        ((tools.jackson.databind.node.ArrayNode) bad.get("qualityGates")).add("TELEPATHY");
        ((tools.jackson.databind.node.ArrayNode) bad.get("testVectors")).remove(1);
        DriverValidator.Report r = DriverValidator.validate(parser, TestSupport.json().writeValueAsString(bad), TestSupport.terminology(),
                Set.of("E03.RESULT"));
        assertThat(r.valid()).isFalse();
        assertThat(r.secretFindings()).containsExactly("protocol.password");
        assertThat(r.errors()).anyMatch(e -> e.contains("systolic is not mapped")).anyMatch(e -> e.contains("TELEPATHY"))
                .anyMatch(e -> e.startsWith("testVectors")).anyMatch(e -> e.startsWith("secrets"));
        assertThat(r.coverage().mapped()).isEqualTo(r.coverage().fields() - 1);
        DriverValidator.Report ok = DriverValidator.validate(parser, sample(), TestSupport.terminology(), Set.of());
        assertThat(ok.valid()).isTrue();
        assertThat(ok.warnings()).anyMatch(w -> w.contains("not deployed"));
        ObjectNode uncoded = (ObjectNode) parser.tree(sample());
        ((ObjectNode) uncoded.get("mappings").get(0)).put("loinc", "00000-0");
        assertThat(DriverValidator.validate(parser, TestSupport.json().writeValueAsString(uncoded), TestSupport.terminology(), Set.of()).errors())
                .anyMatch(e -> e.contains("not in the loaded terminology release"));
        assertThat(List.of(ok.packageId(), ok.version())).containsExactly("vitals.welchallyn.csm-pcd01", "1.0.0");
    }

    @Test
    void fallbackDriversCoverTheFormKeys() {
        DriverPackage p = FallbackDrivers.forCategory(com.wellnessz.casekernel.device.DeviceCodes.Category.VITALS, "OCR",
                java.util.Map.of("systolic", new java.math.BigDecimal("120"), "note", "x", "corrected", Boolean.TRUE));
        assertThat(p.mapping("systolic").orElseThrow().loinc()).isEqualTo("8480-6");
        assertThat(p.mapping("note").orElseThrow().loinc()).isEqualTo("LOCAL:NOTE");
        assertThat(p.bindings().get(0).target()).isEqualTo("E03.RESULT");
        assertThat(p.bindings().get(0).fields()).containsEntry("systolic", "measurements.systolic");
        assertThat(p.qualityGates()).contains("ATTESTATION", "OCR_CONFIDENCE");
        DriverPackage breath = FallbackDrivers.forCategory(com.wellnessz.casekernel.device.DeviceCodes.Category.BREATH_ALCOHOL, "MANUAL",
                java.util.Map.of("brac", new java.math.BigDecimal("0.01")));
        assertThat(breath.bindings().get(0).fields()).containsEntry("brac", "breathScreenResult");
        assertThat(breath.bindings().get(0).target()).isEqualTo("E10.BREATH_ALCOHOL");
    }
}
