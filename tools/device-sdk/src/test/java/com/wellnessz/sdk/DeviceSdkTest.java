package com.wellnessz.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The SDK command line end to end, offline (E31-BL-011): scaffold from a template, validate against the schema and the shipped
 * terminology, conformance against the golden datasets, sign with a local development key and verify, and the diff between versions.
 */
class DeviceSdkTest {

    @TempDir
    Path tmp;

    record Run(int code, String out, String err) {
    }

    Run run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new DeviceSdk(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8)).run(args);
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void scaffoldValidateConformanceSignVerifyAndDiff() throws Exception {
        Run scaffold = run("scaffold", "--adapter", "HL7V2", "--package-id", "vitals.acme.monitor-hl7", "--model", "MD-027", "--out", tmp.toString());
        assertThat(scaffold.code()).as(scaffold.err()).isZero();
        Path pkg = tmp.resolve("vitals.acme.monitor-hl7-0.1.0.json");
        assertThat(pkg).exists();
        assertThat(Files.readString(pkg)).contains("\"packageId\" : \"vitals.acme.monitor-hl7\"").doesNotContain("\"signature\"");

        Run valid = run("validate", pkg.toString());
        assertThat(valid.code()).as(valid.out()).isZero();
        assertThat(valid.out()).startsWith("VALID vitals.acme.monitor-hl7@0.1.0");

        Run conf = run("conformance", pkg.toString());
        assertThat(conf.code()).as(conf.out()).isZero();
        assertThat(conf.out()).contains("PASS normal").contains("PASSED vitals.acme.monitor-hl7@0.1.0 evidenceSha256=");

        assertThat(run("keygen", "--out", tmp.resolve("keys").toString(), "--key-id", "DEV-ACME").code()).isZero();
        Run sign = run("sign", pkg.toString(), "--dev-key", tmp.resolve("keys/dev-key.pk8").toString());
        assertThat(sign.code()).as(sign.err()).isZero();
        assertThat(Files.readString(pkg)).contains("\"keyId\" : \"DEV-ACME\"");
        assertThat(run("verify", pkg.toString(), "--public-key", tmp.resolve("keys/dev-key.pub").toString()).out()).startsWith("VALID");

        // a changed mapping after signing: the signature no longer verifies; diff shows the change and the version check
        Path v2 = tmp.resolve("v2.json");
        Files.writeString(v2, Files.readString(pkg).replace("\"version\" : \"0.1.0\"", "\"version\" : \"0.2.0\"").replace("\"decimals\" : 0", "\"decimals\" : 1"));
        Run tampered = run("verify", v2.toString(), "--public-key", tmp.resolve("keys/dev-key.pub").toString());
        assertThat(tampered.code()).isEqualTo(1);
        assertThat(tampered.out()).startsWith("INVALID");
        Run diff = run("diff", pkg.toString(), v2.toString());
        assertThat(diff.code()).isZero();
        assertThat(diff.out()).contains("~ /version: \"0.1.0\" -> \"0.2.0\"").contains("change(s); version 0.1.0 -> 0.2.0").doesNotContain("WARNING")
                .doesNotContain("/signature");
        Run backwards = run("diff", v2.toString(), pkg.toString());
        assertThat(backwards.out()).contains("WARNING");
    }

    @Test
    void invalidPackagesAndFailingVectorsAreReportedWithExitCodeOne() throws Exception {
        assertThat(run("scaffold", "--adapter", "BLE_GATT", "--package-id", "vitals.acme.scale-ble", "--out", tmp.toString()).code()).isZero();
        Path pkg = tmp.resolve("vitals.acme.scale-ble-0.1.0.json");
        String text = Files.readString(pkg);
        Path justWorks = tmp.resolve("jw.json");
        Files.writeString(justWorks, text.replaceFirst("\"pairing\" : \"[A-Z_]+\"", "\"pairing\" : \"LE_SECURE_CONNECTIONS_JUST_WORKS\""));
        Run invalid = run("validate", justWorks.toString());
        assertThat(invalid.code()).isEqualTo(1);
        assertThat(invalid.out()).startsWith("INVALID").contains("protocol.pairing");
        Path secret = tmp.resolve("secret.json");
        Files.writeString(secret, text.replace("\"bonding\"", "\"password\" : \"hunter2\",\n  \"bonding\""));
        assertThat(run("validate", secret.toString()).out()).contains("secrets: literal secret values found");
        // own golden directory: a wrong expectation fails the conformance run
        Path golden = tmp.resolve("golden");
        Files.createDirectories(golden.resolve("vitals/x"));
        Files.writeString(golden.resolve("vitals/x/normal.json"), "{\"records\":[]}");
        Path wrong = tmp.resolve("wrong.json");
        Files.writeString(wrong, text.replaceFirst("\"input\" : \"golden/[^\"]+normal.json\"", "\"input\" : \"golden/vitals/x/normal.json\""));
        Run conf = run("conformance", wrong.toString(), "--golden", golden.toString());
        assertThat(conf.code()).as(conf.out()).isEqualTo(1);
        assertThat(conf.out()).contains("FAIL normal").contains("FAILED vitals.acme.scale-ble@0.1.0");
        assertThat(run("scaffold", "--adapter", "NOPE", "--package-id", "a.b.c").code()).isEqualTo(2);
        assertThat(run("bogus").code()).isEqualTo(2);
        assertThat(run("sign", pkg.toString(), "--server", "http://example.com", "--token", "x").err()).contains("https");
    }
}
