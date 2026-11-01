package io.intimed;

import io.intimed.common.Json;
import io.intimed.engine.driver.DriverPackage;
import io.intimed.engine.driver.DriverValidator;
import io.intimed.engine.driver.Signatures;
import io.intimed.engine.parse.GattParser;
import io.intimed.engine.parse.ParseException;
import io.intimed.engine.parse.Parsed;
import io.intimed.engine.parse.ParserRegistry;
import io.intimed.engine.parse.XmlParser;
import io.intimed.engine.sim.Simulator;
import io.intimed.engine.terminology.Terminology;
import io.intimed.security.auth.Totp;
import io.intimed.security.crypto.Aead;
import io.intimed.security.privacy.PhiMasker;
import io.intimed.security.secrets.SecretScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests: driver packages (all 26), parsers and robustness (SC-12), terminology, crypto, TOTP, masking, scanning. */
class UnitTests {
    private final ParserRegistry parsers = new ParserRegistry();
    private final Terminology terms = new Terminology();

    static Stream<Resource> drivers() throws Exception {
        return Stream.of(new PathMatchingResourcePatternResolver().getResources("classpath*:drivers/*.json"));
    }

    static DriverPackage load(Resource r) throws Exception {
        return new DriverPackage(Json.parse(r.getContentAsString(StandardCharsets.UTF_8)), "CERTIFIED", "VALID", "test");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("drivers")
    void everyDriverIsValidSignedAndParsesItsGoldenSession(Resource r) throws Exception {
        DriverPackage d = load(r);
        assertThat(DriverValidator.validate(d.json())).isEmpty();
        assertThat(r.createRelative(r.getFilename() + ".sig").exists()).as("signature present").isTrue();
        assertThat(SecretScanner.scan(Json.write(d.json()))).as("no secrets in driver package").isEmpty();
        String payload = Simulator.render(d, Simulator.values(d), "TEST-0001", Instant.parse("2026-11-01T10:00:00Z"), d.tests().get("serial").asString());
        List<Parsed> sets = parsers.get(d.grammar().get("type").asString()).parse(d.grammar(), payload);
        for (JsonNode s : d.semantics()) assertThat(sets.get(0).values).as("key " + s.get("key").asString()).containsKey(s.get("key").asString());
        String bad = Simulator.negative("MALFORMED", d, payload, Simulator.values(d), "T", Instant.now(), "S");
        assertThatThrownBy(() -> parsers.get(d.grammar().get("type").asString()).parse(d.grammar(), bad)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void driverTamperingBreaksTheSignature() {
        KeyPair kp = Signatures.generate();
        ObjectNode pkg = Json.obj().put("manifest", "x");
        String sig = Signatures.sign(kp.getPrivate(), pkg);
        assertThat(Signatures.verify(kp.getPublic(), pkg, sig)).isTrue();
        pkg.put("grammar", "evil");
        assertThat(Signatures.verify(kp.getPublic(), pkg, sig)).isFalse();
    }

    @Test
    void xmlParserRejectsXxeAndDoctype() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><r>&x;</r>";
        assertThatThrownBy(() -> XmlParser.secureParse(xxe)).isInstanceOf(ParseException.class).hasMessageContaining("DOCTYPE");
    }

    @Test
    void sfloatDecodingAndReservedValues() {
        assertThat(GattParser.sfloat(GattParser.encodeSfloat(128, 0), 0)).isEqualTo("128");
        assertThat(GattParser.sfloat(GattParser.encodeSfloat(36.6, -1), 0)).isEqualTo("36.6");
        assertThatThrownBy(() -> GattParser.sfloat(new byte[]{(byte) 0xFF, 0x07}, 0)).isInstanceOf(ParseException.class);
        Map<String, String[]> bp = GattParser.decode("2A35", new byte[]{0x04, (byte) 128, 0, 82, 0, 97, 0, 71, 0});
        assertThat(bp.get("systolic")[0]).isEqualTo("128");
        assertThat(bp.get("pulse")[0]).isEqualTo("71");
        assertThatThrownBy(() -> GattParser.decode("2A35", new byte[]{0x04, 1, 2})).isInstanceOf(ParseException.class);
    }

    @Test
    void serialChecksumAndGdtLengthAreEnforced() throws Exception {
        DriverPackage d = drivers().map(r -> { try { return load(r); } catch (Exception e) { throw new RuntimeException(e); } })
                .filter(x -> x.packageId().equals("da.lifeloc.fc20-serial")).findFirst().orElseThrow();
        String ok = Simulator.render(d, Simulator.values(d), null, Instant.parse("2026-11-01T10:00:00Z"), "FC20-1");
        assertThat(parsers.get("SERIAL").parse(d.grammar(), ok).get(0).values.get("brac").value()).isEqualTo("0.000");
        String tampered = ok.replace("0.000", "0.001");
        assertThatThrownBy(() -> parsers.get("SERIAL").parse(d.grammar(), tampered)).hasMessageContaining("checksum");
        assertThatThrownBy(() -> parsers.get("GDT").parse(Json.obj(), "01380006310\r\n")).isInstanceOf(ParseException.class);
    }

    @Test
    void oversizedPayloadIsRejected() {
        String huge = "x".repeat(1_048_577);
        assertThatThrownBy(() -> parsers.get("JSON").parse(Json.obj(), huge)).hasMessageContaining("exceeds");
    }

    @Test
    void ucumConversions() {
        assertThat(terms.convert(new BigDecimal("98.6"), "[degF]", "Cel", null).orElseThrow()).isEqualByComparingTo("37");
        assertThat(terms.convert(new BigDecimal("16"), "kPa", "mm[Hg]", null).orElseThrow()).isEqualByComparingTo("120.01");
        assertThat(terms.convert(new BigDecimal("180"), "mg/dL", "mmol/L", Map.of("mg/dL>mmol/L", 0.0555)).orElseThrow()).isEqualByComparingTo("9.99");
        assertThat(terms.convert(BigDecimal.ONE, "mm[Hg]", "kg", null)).isEmpty();
        assertThat(terms.ucum("MDC_DIM_BEAT_PER_MIN")).isEqualTo("/min");
    }

    @Test
    void aeadDetectsTamperingAndWrongContext() {
        SecretKeySpec k = new SecretKeySpec(new byte[32], "AES");
        String c = Aead.encryptString(k, "secret", "tenant-a|observation/1");
        assertThat(Aead.decryptString(k, c, "tenant-a|observation/1")).isEqualTo("secret");
        assertThatThrownBy(() -> Aead.decryptString(k, c, "tenant-b|observation/1")).isInstanceOf(SecurityException.class);
    }

    @Test
    void totpMatchesRfc6238Vector() {
        // RFC 6238 SHA-1 secret "12345678901234567890" (Base32 GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ), T = 59 s → 94287082 (last 6 digits 287082)
        assertThat(Totp.code("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", 59 / 30)).isEqualTo("287082");
        assertThat(Totp.verify("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", "abc")).isFalse();
    }

    @Test
    void phiIsMaskedInHl7AndJson() {
        String m = PhiMasker.mask("MSH|^~\\&|A|B|C|D|20260101||ORU^R01|1|P|2.6\rPID|||MRN-1^^^H^MR||DOE^JANE||19800101|F\rOBX|1|NM|8867-4||72|/min");
        assertThat(m).doesNotContain("DOE").doesNotContain("MRN-1").doesNotContain("19800101").contains("OBX|1|NM|8867-4||72");
        assertThat(PhiMasker.mask("{\"subject\":\"MRN-1\",\"hr\":72}")).contains("***").contains("72").doesNotContain("MRN-1");
    }

    @Test
    void secretScannerFindsCredentials() {
        assertThat(SecretScanner.scan("{\"clientSecret\":\"abcd1234efgh\"}")).isNotEmpty();
        assertThat(SecretScanner.scan("{\"clientSecret\":\"secret://vault-ref\"}")).isEmpty();
        assertThat(SecretScanner.scan("-----BEGIN PRIVATE KEY-----")).isNotEmpty();
    }
}
