package com.wellnessz.e32.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Masking test with synthetic names, ids and dates (contract §13.10). */
class TraceMaskerTest {

    @Test
    void hl7PatientSegmentAndObservationValuesAreMasked() {
        String msg = "MSH|^~\\&|CONNEX|LHR_OH|WELLNESSZ|WZ|20261031091405||ORU^R01|MSG0001|P|2.6\r"
                + "PID|1||E1001^^^WZ||Testperson^Erin^Q||19850214|F|||1 Synthetic Street^^London||07700900123\r"
                + "OBX|1|NM|150021^MDC_PRESS_BLD_NONINV_SYS^MDC||124|mm[Hg]|||||F";
        String masked = TraceMasker.mask(msg);
        assertThat(masked).doesNotContain("Testperson", "Erin", "E1001", "19850214", "Synthetic Street", "07700900123", "|124|");
        assertThat(masked).contains("MSH|", "ORU^R01", "150021^MDC_PRESS_BLD_NONINV_SYS", "mm[Hg]", TraceMasker.MASK);
    }

    @Test
    void jsonPersonalMembersEmailsIdsAndDatesAreMasked() {
        String json = "{\"subject\":{\"employeeNumber\":\"E1002\"},\"name\":\"Paul Pilot\",\"dateOfBirth\":\"1979-03-02\","
                + "\"overallFitFactor\":185,\"value\":4.31,\"contact\":\"paul.pilot@example.com\",\"note\":\"felt dizzy\",\"code\":\"20150-9\"}";
        String masked = TraceMasker.mask(json);
        assertThat(masked).doesNotContain("E1002", "Paul Pilot", "1979-03-02", "paul.pilot@example.com", "felt dizzy", "4.31");
        assertThat(masked).contains("\"code\":\"20150-9\"", "overallFitFactor");
    }

    @Test
    void gdtPatientFieldsAndPlainDatesAreMasked() {
        String gdt = "0193000E1003\n0173101Crewmember\n01384208.9\n0148410FEV1\n";
        String masked = TraceMasker.mask(gdt);
        assertThat(masked).doesNotContain("E1003", "Crewmember", "8.9");
        assertThat(masked).contains("FEV1");
        assertThat(TraceMasker.mask("born 14/02/1985 badge S8901")).doesNotContain("14/02/1985", "S8901");
        assertThat(TraceMasker.mask(null)).isNull();
    }
}
