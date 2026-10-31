package com.wellnessz.e32.option;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.wellnessz.e32.support.Docs;
import com.wellnessz.e32.support.FieldIssue;

class OptionPacksTest {

    final OptionPacks packs = new OptionPacks();

    static List<String> codes(List<FieldIssue> issues) {
        return issues.stream().map(FieldIssue::code).toList();
    }

    @Test
    void tenPacksWithSchemasGuidesAndHelpInTwoLanguages() {
        assertThat(packs.codes()).containsExactly("P1", "P2", "P3", "P4", "P5", "P6", "P7", "P8", "P9", "P10");
        for (String o : packs.codes()) {
            Map<String, Object> v = packs.view(o);
            assertThat(v).containsKeys("name", "howItWorks", "typicalDevices", "parameterSchema", "commonSchema", "prerequisites", "stepGuide",
                    "diagramUrl", "securityLevel", "reliability", "effort", "help");
            assertThat(Docs.map(v.get("help"))).containsKeys("en", "ar");
            assertThat(Docs.str(Docs.map(v.get("parameterSchema")).get("$schema"))).contains("2020-12");
            assertThat(Docs.list(v.get("stepGuide"))).isNotEmpty();
        }
        assertThat(packs.comparison("P4")).containsEntry("securityLevel", "HIGH").containsKey("prerequisites");
    }

    @Test
    void serialFramingAndUsbAllowList() {
        Map<String, Object> schema = packs.parameterSchema("P1");
        Map<String, Object> p = Docs.merge(ParameterValidator.defaults(schema), Docs.of("hostEndpointId", "EP-LHR-AGT-01", "port", "COM3"));
        assertThat(ParameterValidator.validate(schema, p)).isEmpty();
        assertThat(codes(ParameterValidator.validate(schema, Docs.merge(p, Docs.of("baud", 12345))))).contains("enum");
        assertThat(codes(ParameterValidator.validate(schema, Docs.merge(p, Docs.of("framing", Docs.of("type", "LINE", "maxFrameBytes", 70000))))))
                .contains("maximum");
        Map<String, Object> usb = Docs.merge(p, Docs.of("connection", "USB_CDC"));
        assertThat(codes(OptionRules.semantic("P1", usb, null))).contains("required");
        Map<String, Object> usbOk = Docs.merge(usb, Docs.of("usbAllowList", List.of(Docs.of("vid", "0403", "pid", "6001"))));
        assertThat(ParameterValidator.validate(schema, usbOk)).isEmpty();
        assertThat(OptionRules.semantic("P1", usbOk, null)).isEmpty();
        assertThat(codes(ParameterValidator.validate(schema, Docs.merge(usbOk, Docs.of("usbAllowList", List.of(Docs.of("vid", "XYZ", "pid", "6001")))))))
                .contains("pattern");
        assertThat(codes(ParameterValidator.validate(schema, Docs.merge(p, Docs.of("unexpected", 1))))).contains("unknown_parameter");
    }

    @Test
    void vendorDatabaseMustBeReadOnlyAndSecretsAreReferences() {
        Map<String, Object> schema = packs.parameterSchema("P2");
        assertThat(ParameterValidator.secretPaths(schema)).containsExactly("dbCredentialRef");
        Map<String, Object> db = Docs.of("source", "DB_QUERY", "dialect", "SQLSERVER", "connection", Docs.of("host", "benson-pc", "database", "Benson"),
                "dbCredentialRef", "devsec:2f7c", "query", "select * from v_wz_audiogram where modified_at > :watermark", "watermarkColumn", "modified_at",
                "hostEndpointId", "EP-LHR-AGT-01");
        assertThat(ParameterValidator.validate(schema, db)).isEmpty();
        assertThat(OptionRules.semantic("P2", db, null)).isEmpty();
        assertThat(codes(ParameterValidator.validate(schema, Docs.merge(db, Docs.of("dbCredentialRef", "Pa55word!"))))).contains("secret_inline");
        assertThat(codes(OptionRules.semantic("P2", Docs.merge(db, Docs.of("query", "select 1; delete from audiogram")), null)))
                .contains("query_not_read_only");
        assertThat(OptionRules.readOnlySelect("select * from t into outfile")).isFalse();
        assertThat(OptionRules.readOnlySelect("update t set x = 1")).isFalse();
        assertThat(codes(OptionRules.semantic("P2", Docs.of("source", "FOLDER_WATCH"), null))).contains("required");
    }

    @Test
    void bluetoothPairingMllpTlsAndPoctQc() {
        assertThat(codes(OptionRules.semantic("P3", Docs.of("pairing", "JUST_WORKS_EXCEPTION"), null))).contains("pairing_exception_required");
        Map<String, Object> mllp = Docs.of("deviceAddress", Docs.of("ip", "10.0.0.5"), "mllp", Docs.of("listenerEndpointId", "EP-LHR-GW-01", "port", 2575,
                "tls", "EXCEPTION_SEGMENTED_VLAN"), "msh", Docs.of("sendingApplication", "CONNEX", "sendingFacility", "LHR", "receivingApplication",
                "WELLNESSZ", "receivingFacility", "WZ"), "ackMode", "ORIGINAL", "messageProfile", "IHE_PCD_01");
        assertThat(ParameterValidator.validate(packs.parameterSchema("P4"), mllp)).isEmpty();
        assertThat(codes(OptionRules.semantic("P4", mllp, new OptionRules.Context(List.of(), "UK", "CORPORATE"))))
                .contains("plaintext_exception_required", "zone_violation");
        assertThat(codes(ParameterValidator.validate(packs.parameterSchema("P4"), Docs.merge(mllp, Docs.of("mllp", Docs.of("port", 80))))))
                .contains("minimum");
        assertThat(codes(OptionRules.semantic("P5", Docs.of("qcLotPolicy", Docs.of("requirePassingQc", false, "qcWindowHours", 24,
                "newLotRequiresQc", true)), null))).contains("cannot_relax");
    }

    @Test
    void cloudDicomFhirOcrAndManualRules() {
        Map<String, Object> cloud = Docs.of("apiBaseUrl", "https://api.vendor.example/v1", "oauth2", Docs.of("tokenUrl", "https://auth.vendor.example/t",
                "clientId", "wz", "clientSecretRef", "devsec:1"), "webhook", Docs.of("enabled", true, "webhookSecretRef", "devsec:2"),
                "poll", Docs.of("enabled", true, "intervalSeconds", 300), "residencyRegion", "EU");
        assertThat(ParameterValidator.validate(packs.parameterSchema("P6"), cloud)).isEmpty();
        assertThat(codes(OptionRules.semantic("P6", cloud, new OptionRules.Context(List.of(), "UK", null)))).contains("host_not_allowed", "residency_check");
        assertThat(OptionRules.semantic("P6", cloud, new OptionRules.Context(List.of("api.vendor.example"), "EU", null))).isEmpty();
        assertThat(codes(ParameterValidator.validate(packs.parameterSchema("P6"), Docs.merge(cloud, Docs.of("poll", Docs.of("intervalSeconds", 10))))))
                .contains("minimum");
        assertThat(codes(ParameterValidator.validate(packs.parameterSchema("P7"), Docs.of("tls", "NONE")))).contains("const");
        assertThat(codes(ParameterValidator.validate(packs.parameterSchema("P7"), Docs.of("aeTitles", Docs.of("calling", "lower case", "called", "WZ")))))
                .contains("pattern");
        assertThat(codes(OptionRules.semantic("P8", Docs.of("auth", "SMART_BACKEND"), null))).contains("required");
        assertThat(codes(OptionRules.semantic("P9", Docs.of("regions", List.of(Docs.of("key", "bll", "box", List.of(0.6, 0.3, 0.2, 0.5)))), null)))
                .contains("box_invalid");
        assertThat(codes(ParameterValidator.validate(packs.parameterSchema("P9"), Docs.of("minConfidence", 0.3)))).contains("minimum");
        assertThat(codes(OptionRules.semantic("P10", Docs.of("attestationFields", List.of("MODEL", "SERIAL", "CALIBRATION_ID", "MODEL")), null)))
                .contains("attestation_incomplete");
    }

    @Test
    void driverProtocolBecomesWizardDefaults() {
        Map<String, Object> p4 = OptionRules.fromPackage("P4", Docs.of("mllpPort", 2575, "tls", "REQUIRED", "profile", "IHE_PCD_01", "ackMode", "ORIGINAL"),
                packs.parameterSchema("P4"));
        assertThat(Docs.at(p4, "mllp.port")).isEqualTo(2575);
        assertThat(p4).containsEntry("messageProfile", "IHE_PCD_01").containsEntry("ackMode", "ORIGINAL");
        Map<String, Object> p1 = OptionRules.fromPackage("P1", Docs.of("baud", 9600, "framing", Docs.of("type", "LINE", "terminator", "\r\n")),
                packs.parameterSchema("P1"));
        assertThat(p1).containsEntry("baud", 9600);
        Map<String, Object> p3 = OptionRules.fromPackage("P3", Docs.of("service", "0x1810", "characteristic", "0x2A35"), packs.parameterSchema("P3"));
        assertThat(Docs.strings(p3.get("characteristics"))).containsExactly("0x2A35");
        Map<String, Object> p6 = OptionRules.fromPackage("P6", Docs.of("signature", Docs.of("header", "X-Signature", "replayWindowSeconds", 300),
                "poll", Docs.of("enabled", true, "url", "PARAMETER:apiBaseUrl", "intervalSeconds", 300)), packs.parameterSchema("P6"));
        assertThat(Docs.at(p6, "webhook.signatureHeader")).isEqualTo("X-Signature");
        assertThat(Docs.at(p6, "poll.intervalSeconds")).isEqualTo(300);
        assertThat(OptionRules.hostEndpoint("P4", Docs.of("mllp", Docs.of("listenerEndpointId", "EP-LHR-GW-01")))).isEqualTo("EP-LHR-GW-01");
        assertThat(OptionRules.endpointKind("P3", "MOBILE_APP", Docs.of("bondingOwner", "KIOSK"))).isEqualTo("KIOSK");
        assertThat(ParameterValidator.hostname("benson-pc.lhr.example")).isTrue();
        assertThat(ParameterValidator.hostname("-bad-.example")).isFalse();
        assertThat(ParameterValidator.hostname("a..b")).isFalse();
        Map<String, Object> common = ParameterValidator.defaults(packs.commonSchema());
        assertThat(common).containsEntry("timeSource", "NTP_GATEWAY").containsEntry("verifyBeforePost", true);
        assertThat(codes(ParameterValidator.validate(packs.commonSchema(), Docs.merge(common, Docs.of("timeZone", "Mars/Olympus"))))).contains("time_zone");
    }
}
