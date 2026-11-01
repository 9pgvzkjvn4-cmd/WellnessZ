package com.wellnessz.e31.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceObservation;
import com.wellnessz.e31.TestSupport;
import com.wellnessz.e31.adapter.fhir.MiniFhirPath;
import com.wellnessz.e31.routing.Router;
import com.wellnessz.e31.web.WebhookController;

import tools.jackson.databind.JsonNode;

/** Deterministic ids, masking, time formats, bindings, FHIRPath subset and webhook HMAC. */
class SupportTest {

    @Test
    void observationIdsAreDeterministicAndOrderIndependent() {
        UUID m = UUID.fromString("00000000-0000-4000-8000-000000000001");
        assertThat(Ids.observationId(m, 0, List.of("b", "a"))).isEqualTo(Ids.observationId(m, 0, List.of("a", "b")));
        assertThat(Ids.observationId(m, 1, List.of("a"))).isNotEqualTo(Ids.observationId(m, 0, List.of("a")));
        UUID v5 = Ids.uuidV5("x");
        assertThat(v5.version()).isEqualTo(5);
        assertThat(v5.variant()).isEqualTo(2);
        assertThat(Ids.sha256Hex("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(Ids.constantTimeEquals("AB", "ab")).isTrue();
        assertThat(Ids.constantTimeEquals("ab", null)).isFalse();
    }

    @Test
    void masksValuesButKeepsStructure() {
        String hl7 = Masking.excerpt("MSH|^~\\&|CSM|LHR|||20261030||ORU^R01|1|P|2.6\rPID|||E1001||Doe^Jane\rOBX|1|NM|150021^SYS^MDC||124|mmHg\r"
                .getBytes(StandardCharsets.UTF_8), "HL7V2");
        assertThat(hl7).contains("ORU^R01").contains("150021^SYS^MDC").doesNotContain("E1001").doesNotContain("Doe").doesNotContain("124");
        String json = Masking.excerpt("{\"employeeId\":\"E1001\",\"value\":124,\"ok\":true}".getBytes(StandardCharsets.UTF_8), "FHIR");
        assertThat(json).contains("employeeId").doesNotContain("E1001").doesNotContain("124");
        String xml = Masking.excerpt("<A><PT V=\"E1001\"/><N>Jane</N></A>".getBytes(StandardCharsets.UTF_8), "POCT1A");
        assertThat(xml).contains("<PT").doesNotContain("E1001").doesNotContain("Jane");
        String csv = Masking.excerpt("EmployeeNo,L500\r\nE1001,10\r\n".getBytes(StandardCharsets.UTF_8), "FILE");
        assertThat(csv).contains("EmployeeNo").doesNotContain("E1001");
        assertThat(Masking.detail("Order 123456 failed")).doesNotContain("123456");
    }

    @Test
    void timeFormats() {
        assertThat(TimeParsing.parse("20261030091405+0100", "yyyyMMddHHmmssZ", null)).isEqualTo(Instant.parse("2026-10-30T08:14:05Z"));
        assertThat(TimeParsing.parse("202610300914", "yyyyMMddHHmmss", ZoneId.of("UTC"))).isEqualTo(Instant.parse("2026-10-30T09:14:00Z"));
        assertThat(TimeParsing.parse("2026-07-01T10:00:00", "ISO", ZoneId.of("Europe/London"))).isEqualTo(Instant.parse("2026-07-01T09:00:00Z"));
        assertThat(TimeParsing.parse("1793440800000", "EPOCH_MILLIS", null)).isEqualTo(Instant.ofEpochMilli(1793440800000L));
        assertThat(TimeParsing.date("14031981")).hasToString("1981-03-14");
        assertThat(TimeParsing.date("19850412")).hasToString("1985-04-12");
    }

    @Test
    void boundFieldsFromComponentsParametersConstantsAndDerivedValues() {
        Router.Selection s = new Router.Selection("E07.IH_MEASUREMENT", "1", DeviceCodes.Category.EXPOSURE, "E07.IH_MEASUREMENT",
                Map.of("lex8h", "value", "param:segCode", "segCode", "const:NOISE_DOSIMETRY", "measurementType", "ageYears", "measurements.ageYears",
                        "missing", "x"), false, List.of());
        Map<String, Object> f = Router.boundFields(s, List.of(new DeviceObservation.Component("lex8h", null, "L", "d", "QUANTITY", new BigDecimal("86.4"),
                null, "dB", null, null, null)), Map.of("ageYears", 41), Map.of("segCode", "SEG-RAMP"));
        assertThat(f).containsEntry("value", new BigDecimal("86.4")).containsEntry("segCode", "SEG-RAMP")
                .containsEntry("measurementType", "NOISE_DOSIMETRY").containsEntry("measurements.ageYears", 41).doesNotContainKey("x");
    }

    @Test
    void miniFhirPath() {
        JsonNode obs = TestSupport.json().readTree("""
                {"resourceType":"Observation","component":[{"code":{"coding":[{"code":"8480-6"}]},"valueQuantity":{"value":120}},
                 {"code":{"coding":[{"code":"8462-4"}]},"valueQuantity":{"value":80}}],"subject":{"identifier":{"value":"E1"}}}""");
        assertThat(MiniFhirPath.first(List.of(obs), "Observation.component.where(code.coding.code='8462-4').valueQuantity.value")).isEqualTo("80");
        assertThat(MiniFhirPath.first(List.of(obs), "Observation.subject.identifier.value")).isEqualTo("E1");
        assertThat(MiniFhirPath.first(List.of(obs), "Device.id")).isNull();
    }

    @Test
    void webhookHmacOverTimestampAndBody() throws Exception {
        byte[] body = "{\"overallFitFactor\":245}".getBytes(StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("s3cret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update("1793440800.".getBytes(StandardCharsets.UTF_8));
        String sig = java.util.HexFormat.of().formatHex(mac.doFinal(body));
        assertThat(WebhookController.valid("s3cret".toCharArray(), "1793440800", body, sig)).isTrue();
        assertThat(WebhookController.valid("s3cret".toCharArray(), "1793440800", body, "sha256=" + sig)).isTrue();
        assertThat(WebhookController.valid("s3cret".toCharArray(), "1793440801", body, sig)).isFalse();
        assertThat(WebhookController.valid("other".toCharArray(), "1793440800", body, sig)).isFalse();
        assertThat(WebhookController.parseTimestamp("1793440800")).isEqualTo(Instant.ofEpochSecond(1793440800L));
        assertThat(WebhookController.parseTimestamp("garbage")).isNull();
    }
}
