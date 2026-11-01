package com.wellnessz.e31.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.wellnessz.e31.adapter.hl7.Hl7Support;
import com.wellnessz.e31.service.HistoricRetrievalService;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.util.Terser;

/**
 * Outbound HL7 v2.5 built with HAPI (E31-HLR-08, E31-BL-004, E31-BL-016): ORM^O01, OML^O21, ADT^A04 / A08 and the QRY^R02 of a
 * historic retrieval parse back with HAPI and carry the order, the minimised demographics and the range; the deterministic
 * key of historic results ignores the per-transmission MSH fields.
 */
class Hl7OutboundBuilderTest {

    static final Instant NOW = Instant.parse("2026-11-01T09:00:00Z");
    static final Hl7OutboundBuilder.Header H = new Hl7OutboundBuilder.Header("WELLNESSZ", "LHR-OH", "MUSE", "LHR");
    final Hl7OutboundBuilder builder = new Hl7OutboundBuilder();
    final Hl7Support hl7 = new Hl7Support();

    Hl7OutboundBuilder.Order order(LocalDate dob, String sex) {
        return new Hl7OutboundBuilder.Order("ORD-7001", "E1001", "Jane Doe", dob, sex, Instant.parse("2026-11-01T10:30:00Z"), "ECG", null);
    }

    @Test
    void ormO01CarriesTheNewOrderForTheEmployee() throws HL7Exception {
        String er7 = builder.order("ORM^O01", H, order(LocalDate.of(1980, 4, 2), "F"), "WZ1-0", NOW);
        Message m = hl7.parse(er7);
        Terser t = new Terser(m);
        assertThat(t.get("/MSH-9-1") + "^" + t.get("/MSH-9-2")).isEqualTo("ORM^O01");
        assertThat(t.get("/MSH-3-1")).isEqualTo("WELLNESSZ");
        assertThat(t.get("/MSH-5-1")).isEqualTo("MUSE");
        assertThat(t.get("/MSH-10")).isEqualTo("WZ1-0");
        assertThat(t.get("/PATIENT/PID-3-1")).isEqualTo("E1001");
        assertThat(t.get("/PATIENT/PID-5-1")).isEqualTo("Doe");
        assertThat(t.get("/PATIENT/PID-5-2")).isEqualTo("Jane");
        assertThat(t.get("/PATIENT/PID-7")).isEqualTo("19800402");
        assertThat(t.get("/PATIENT/PID-8")).isEqualTo("F");
        assertThat(t.get("/ORDER/ORC-1")).isEqualTo("NW");
        assertThat(t.get("/ORDER/ORC-2-1")).isEqualTo("ORD-7001");
        assertThat(t.get("/ORDER/ORDER_DETAIL/OBR-4-1")).isEqualTo("ECG");
        assertThat(t.get("/ORDER/ORDER_DETAIL/OBR-6")).isEqualTo("20261101103000");
    }

    @Test
    void omlO21AndAdtA04A08AreBuiltAndDemographicsStayMinimised() throws HL7Exception {
        String oml = builder.order("OML^O21", H, order(null, null), "WZ1-1", NOW);
        Terser t = new Terser(hl7.parse(oml));
        assertThat(t.get("/MSH-9-1") + "^" + t.get("/MSH-9-2")).isEqualTo("OML^O21");
        assertThat(t.get("/ORDER/OBSERVATION_REQUEST/OBR-2-1")).isEqualTo("ORD-7001");
        assertThat(oml).doesNotContain("19800402"); // no date of birth unless the driver declares it needs it
        for (String type : new String[] {"ADT^A04", "ADT^A08"}) {
            String adt = builder.order(type, H, order(null, "M"), "WZ1-2", NOW);
            Terser a = new Terser(hl7.parse(adt));
            assertThat(a.get("/MSH-9-1") + "^" + a.get("/MSH-9-2")).isEqualTo(type);
            assertThat(a.get("/EVN-1")).isEqualTo(type.substring(4));
            assertThat(a.get("/PID-3-1")).isEqualTo("E1001");
            assertThat(a.get("/PID-8")).isEqualTo("M");
            assertThat(a.get("/PV1-2")).isEqualTo("O");
            assertThat(a.get("/PV1-19-1")).isEqualTo("ORD-7001");
        }
        assertThatThrownBy(() -> builder.order("ORU^R01", H, order(null, null), "x", NOW)).isInstanceOf(HL7Exception.class);
    }

    @Test
    void qryR02AsksForTheDeviceResultsOfTheRange() throws HL7Exception {
        String q = builder.query(H, "MAC-1", Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-31T00:00:00Z"), 100, "q1", "WZQ1", NOW);
        Terser t = new Terser(hl7.parse(q));
        assertThat(t.get("/MSH-9-1") + "^" + t.get("/MSH-9-2")).isEqualTo("QRY^R02");
        assertThat(t.get("/QRD-2")).isEqualTo("R");
        assertThat(t.get("/QRD-7-1")).isEqualTo("100");
        assertThat(t.get("/QRD-8-1")).isEqualTo("MAC-1");
        assertThat(t.get("/QRF-2")).isEqualTo("20261001000000");
        assertThat(t.get("/QRF-3")).isEqualTo("20261031000000");
    }

    @Test
    void historicKeysIgnoreTransmissionFieldsButNotContent() {
        String a = "MSH|^~\\&|MUSE|LHR|WZ||20261030101500||ORU^R01|C-1|P|2.5\rPID|1||E1001\rOBX|1|NM|8867-4||72|/min\r";
        String b = a.replace("20261030101500", "20261101090000").replace("C-1", "C-99");
        String c = a.replace("||72|", "||73|");
        String ka = HistoricRetrievalService.deterministicKey("DEV-1", "HL7V2", a.getBytes());
        assertThat(HistoricRetrievalService.deterministicKey("DEV-1", "HL7V2", b.getBytes())).isEqualTo(ka);
        assertThat(HistoricRetrievalService.deterministicKey("DEV-1", "HL7V2", c.getBytes())).isNotEqualTo(ka);
        assertThat(HistoricRetrievalService.deterministicKey("DEV-2", "HL7V2", a.getBytes())).isNotEqualTo(ka);
    }
}
