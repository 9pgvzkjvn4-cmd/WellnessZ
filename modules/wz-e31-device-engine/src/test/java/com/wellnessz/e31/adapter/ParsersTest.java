package com.wellnessz.e31.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.wellnessz.e31.TestSupport;
import com.wellnessz.e31.adapter.file.CsvParser;
import com.wellnessz.e31.adapter.hl7.Hl7Segments;
import com.wellnessz.e31.adapter.hl7.Hl7Support;
import com.wellnessz.e31.driver.BundledDrivers;
import com.wellnessz.e31.driver.DriverPackage;
import com.wellnessz.e31.simulator.GoldenDatasets;
import com.wellnessz.e31.support.EngineException;

/** Every parse type of the adapter library against its golden vectors and hand-written edge cases. */
class ParsersTest {

    static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");
    final BundledDrivers bundled = TestSupport.bundled();
    final GoldenDatasets golden = TestSupport.golden();

    List<ParsedRecord> parse(String packageId, String dataset) {
        DriverPackage p = bundled.get(packageId, null).orElseThrow();
        return TestSupport.adapters().parse(new ParseContext(p, golden.content("golden/" + dataset), null, Map.of(), NOW, ZoneOffset.UTC));
    }

    String code(String packageId, String dataset) {
        try {
            parse(packageId, dataset);
            return null;
        } catch (EngineException e) {
            return e.code();
        }
    }

    @Test
    void hl7Pcd01SelectorsUnitsIdentityAndDeviceTime() {
        ParsedRecord r = parse("vitals.welchallyn.csm-pcd01", "vitals/csm-pcd01/normal.hl7").get(0);
        assertThat(r.values().get("systolic").number()).isEqualByComparingTo("124");
        assertThat(r.values().get("systolic").unit()).isEqualTo("mm[Hg]");    // MDC_DIM_MMHG → UCUM
        assertThat(r.values().get("pulse").unit()).isEqualTo("/min");
        assertThat(r.values().get("cuffSize").text()).isEqualTo("ADULT");
        assertThat(r.identity().employeeId()).isEqualTo("E1001");
        assertThat(r.identity().deviceSerial()).isEqualTo("CSM-100-4411");
        assertThat(r.identity().dateOfBirth()).hasToString("1985-04-12");
        assertThat(r.identity().sex()).isEqualTo("F");
        assertThat(r.identity().deviceTime()).isEqualTo(Instant.parse("2026-10-30T09:14:05Z"));
        assertThat(code("vitals.welchallyn.csm-pcd01", "vitals/csm-pcd01/missing-pid.hl7")).isEqualTo("parse_failed");
    }

    @Test
    void hl7SegmentModelAndEscapes() {
        Hl7Segments s = new Hl7Segments("MSH|^~\\&|A|B|C|D|20261030||ORU^R01|X1|P|2.6\rPID|||E1^^^WZ||Smith\\S\\Jr^Ann\rOBX|1|NM|150021^SYS^MDC||120|mmHg\r");
        assertThat(s.value("MSH-9.1")).isEqualTo("ORU");
        assertThat(s.value("MSH-10")).isEqualTo("X1");
        assertThat(s.value("PID-3.1")).isEqualTo("E1");
        assertThat(s.value("PID-5.1")).isEqualTo("Smith^Jr");
        assertThat(s.value("OBX[OBX-3.1=150021]/OBX-5")).isEqualTo("120");
        assertThat(s.unit("OBX[OBX-3.1=150021]/OBX-5")).isEqualTo("mmHg");
        assertThat(s.value("OBX[OBX-3.1=999999]/OBX-5")).isNull();
        assertThatThrownBy(() -> s.value("not a selector")).isInstanceOf(EngineException.class);
    }

    @Test
    void hl7AcknowledgementsAaAeAr() throws Exception {
        String raw = new String(golden.content("golden/vitals/csm-pcd01/normal.hl7"), StandardCharsets.ISO_8859_1);
        Hl7Support hapi = new Hl7Support();
        String aa = hapi.ack(hapi.parse(raw), "AA", null, raw, java.time.Clock.systemUTC());
        assertThat(aa).contains("MSA|AA|MSG0001");
        String ae = Hl7Support.ack("garbage", "AE", "parse_failed", java.time.Clock.systemUTC());
        assertThat(ae).contains("MSA|AE|").contains("ERR|||100");
        String ar = Hl7Support.ack(raw, "AR", "unknown_device", java.time.Clock.systemUTC());
        assertThat(ar).contains("MSA|AR|MSG0001").contains("ERR|||207").startsWith("MSH|^~\\&|WELLNESSZ|WZ|CSM|LHR-OH-ROOM-1|");
    }

    @Test
    void gdtBlocksUnitsDemographicsAndCharset() {
        ParsedRecord r = parse("spiro.ndd.easyone-connect-gdt", "spirometry/easyone-gdt/normal.gdt").get(0);
        assertThat(r.values().get("fev1L").number()).isEqualByComparingTo("3.85");
        assertThat(r.values().get("pefLs").unit()).isEqualTo("L/s");
        assertThat(r.values().get("sessionGrade").text()).isEqualTo("A");
        assertThat(r.identity().employeeId()).isEqualTo("E1002");
        assertThat(r.identity().heightCm()).isEqualByComparingTo("180");
        assertThat(r.identity().sex()).isEqualTo("M");
        assertThat(r.identity().name()).isEqualTo("PaulPilot");
        assertThat(r.identity().deviceTime()).isEqualTo(Instant.parse("2026-10-30T09:14:05Z")); // Europe/London, GMT on 30 Oct
        assertThat(code("spiro.ndd.easyone-connect-gdt", "spirometry/easyone-gdt/malformed.gdt")).isEqualTo("parse_failed");
    }

    @Test
    void csvRowsQuotesAndColumnMismatch() {
        assertThat(CsvParser.rows("a,b\r\n\"x,1\",\"he said \"\"hi\"\"\"\r\n", ',')).containsExactly(List.of("a", "b"), List.of("x,1", "he said \"hi\""));
        ParsedRecord r = parse("audio.benson.cca200-db", "audiometry/cca200-db/normal.csv").get(0);
        assertThat(r.values().get("left.4000").number()).isEqualByComparingTo("20");
        assertThat(r.values().get("ambientNoiseFlag").text()).isEqualTo("false");
        assertThat(r.identity().ageYears()).isEqualTo(41);
        assertThat(code("audio.benson.cca200-db", "audiometry/cca200-db/malformed.csv")).isEqualTo("parse_failed");
        assertThat(parse("exp.casella.dbadge-csv", "exposure/dbadge-csv/edge.csv")).hasSize(2);
    }

    @Test
    void xpathWithConcatAndStringJoinAndPoct1aRecords() {
        ParsedRecord ecg = parse("ecg.ge.muse-xml", "cardiology/muse-xml/normal.xml").get(0);
        assertThat(ecg.values().get("interpretation").text()).isEqualTo("Normal sinus rhythm Normal ECG");
        assertThat(ecg.identity().deviceTime()).isEqualTo(Instant.parse("2026-10-30T09:30:00Z"));
        ParsedRecord poc = parse("poc.abbott.afinion2-poct1a", "poc/afinion2-poct1a/normal.xml").get(0);
        assertThat(poc.values().get("hba1c").number()).isEqualByComparingTo("5.4");
        assertThat(poc.values().get("qcLot").text()).isEqualTo("AF-LOT-2026-10");
        assertThat(poc.identity().operator()).isEqualTo("nurse1");
        assertThat(code("poc.abbott.afinion2-poct1a", "poc/afinion2-poct1a/malformed.xml")).isEqualTo("parse_failed");
    }

    @Test
    void xmlParserRefusesExternalEntities() {
        DriverPackage p = bundled.get("ecg.ge.muse-xml", null).orElseThrow();
        byte[] xxe = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><RestingECG>&x;</RestingECG>".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> TestSupport.adapters().parse(new ParseContext(p, xxe, null, Map.of(), NOW, ZoneOffset.UTC)))
                .isInstanceOf(EngineException.class).extracting("code").isEqualTo("parse_failed");
    }

    @Test
    void gattProfilesBlpHtpPlxWssBcs() {
        ParsedRecord bp = parse("vitals.and.ua651ble-blp", "vitals/ua651ble-blp/edge.json").get(0);
        assertThat(bp.values().get("systolic").unit()).isEqualTo("kPa");
        assertThat(bp.values().get("systolic").number()).isEqualByComparingTo("16.3");
        assertThat(bp.identity().deviceTime()).isEqualTo(Instant.parse("2026-10-30T09:20:00Z"));
        assertThat(parse("vitals.withings.thermo-htp", "vitals/thermo-htp/edge.json").get(0).values().get("temperatureC").unit()).isEqualTo("[degF]");
        ParsedRecord plx = parse("vitals.nonin.plx-ble", "vitals/nonin-plx/edge.json").get(0);
        assertThat(plx.values().get("spo2").number()).isEqualByComparingTo("100");
        assertThat(plx.identity().deviceTime()).isEqualTo(Instant.parse("2026-10-30T09:20:00Z")); // no device time: envelope receivedAt
        ParsedRecord wss = parse("vitals.tanita.wss-ble", "vitals/tanita-wss/edge.json").get(0);
        assertThat(wss.values().get("weightKg").unit()).isEqualTo("[lb_av]");
        assertThat(wss.values().get("heightCm").unit()).isEqualTo("[in_i]");
        ParsedRecord bcs = parse("vitals.withings.bodycomp-bcs", "vitals/withings-bcs/normal.json").get(0);
        assertThat(bcs.values().get("bodyFatPct").number()).isEqualByComparingTo("22.5");
        assertThat(bcs.values()).containsKeys("weightKg", "heightCm").doesNotContainKey("impedanceOhm");
        assertThat(code("vitals.and.ua651ble-blp", "vitals/ua651ble-blp/malformed.json")).isEqualTo("parse_failed");
    }

    @Test
    void serialRegexFramesAndUnknownFrames() {
        ParsedRecord r = parse("da.lifeloc.fc20-serial", "drug-alcohol/fc20-serial/edge.json").get(0);
        assertThat(r.values().get("brac").number()).isEqualByComparingTo("0.042");
        assertThat(r.values().get("sequence").text()).isEqualTo("CONFIRM");
        assertThat(r.identity().deviceSerial()).isEqualTo("FC20-30112");
        assertThat(code("da.lifeloc.fc20-serial", "drug-alcohol/fc20-serial/malformed.json")).isEqualTo("parse_failed");
    }

    @Test
    void fhirBundleFhirPathAndProfileRules() {
        ParsedRecord r = parse("wear.aggregator.fhir-pcha", "wearable/fhir-pcha/normal.json").get(0);
        assertThat(r.values().get("steps").number()).isEqualByComparingTo("8432");
        assertThat(r.values().get("sleepMinutes").unit()).isEqualTo("min");
        assertThat(r.identity().employeeId()).isEqualTo("E1001");
        assertThat(code("wear.aggregator.fhir-pcha", "wearable/fhir-pcha/malformed.json")).isEqualTo("profile_violation");
    }

    @Test
    void jsonPointerDicomJsonAndForm() {
        ParsedRecord fit = parse("fit.tsi.link-webhook", "resp-ergonomics/tsi-link/normal.json").get(0);
        assertThat(fit.values().get("pass").text()).isEqualTo("true");
        assertThat(fit.values().get("exercises").text()).contains("HEAD_UP_DOWN");
        assertThat(code("fit.tsi.link-webhook", "resp-ergonomics/tsi-link/malformed.json")).isEqualTo("parse_failed");
        ParsedRecord cxr = parse("img.cxr.dicomweb", "imaging/cxr-dicomweb/normal.json").get(0);
        assertThat(cxr.values().get("modality").text()).isEqualTo("DX");
        assertThat(cxr.identity().name()).isEqualTo("Employee^Erin");
        assertThat(cxr.identity().deviceTime()).isEqualTo(Instant.parse("2026-10-30T10:15:00Z"));
        assertThat(code("img.cxr.dicomweb", "imaging/cxr-dicomweb/malformed.json")).isEqualTo("profile_violation");
        ParsedRecord manual = parse("vision.manual.snellen", "vision/manual-snellen/normal.json").get(0);
        assertThat(manual.values().get("binocular").text()).isEqualTo("6/6");
        assertThat(manual.identity().employeeId()).isEqualTo("E1001");
        assertThat(code("vision.manual.snellen", "vision/manual-snellen/malformed.json")).isEqualTo("parse_failed");
    }
}
