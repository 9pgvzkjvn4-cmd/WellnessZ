package com.wellnessz.e33.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

class CaptureKpisAndOcrTest {

    static CaptureKpis.SessionFact s(String state, String path, String minutes, int retests) {
        return new CaptureKpis.SessionFact(state, path, new BigDecimal(minutes), retests);
    }

    @Test
    void usageMinutesSavedRetestAndUnmatchedRates() {
        List<CaptureKpis.SessionFact> sessions = List.of(
                s("ACCEPTED", "DEVICE", "4.0", 0), s("ACCEPTED", "DEVICE", "3.0", 1), s("ACCEPTED", "ALTERNATE_DEVICE", "3.0", 0),
                s("ACCEPTED", "OCR_VERIFIED", "4.0", 0), s("ACCEPTED", "MANUAL_ATTESTED", "3.0", 0), s("REJECTED", null, "3.0", 0),
                s("EXPIRED", null, "3.0", 0));
        List<CaptureKpis.NoticeFact> notices = List.of(new CaptureKpis.NoticeFact(false), new CaptureKpis.NoticeFact(true),
                new CaptureKpis.NoticeFact(false), new CaptureKpis.NoticeFact(false));
        CaptureKpis.Result r = CaptureKpis.compute(sessions, notices);
        assertThat(r.sessions()).isEqualTo(7);
        assertThat(r.accepted()).isEqualTo(5);
        assertThat(r.deviceCaptures()).isEqualTo(3);
        assertThat(r.usagePct()).isEqualByComparingTo("60.00");
        assertThat(r.minutesSaved()).isEqualByComparingTo("12.0"); // 4 + 3 + 3 + 4/2
        assertThat(r.minutesSavedPerDeviceTest()).isEqualByComparingTo("4.0");
        assertThat(r.retests()).isEqualTo(1);
        assertThat(r.retestRatePct()).isEqualByComparingTo("14.29"); // 1 / (5 + 1 + 1)
        assertThat(r.unmatchedRatePct()).isEqualByComparingTo("25.00");
        assertThat(r.byPath()).containsEntry("OCR_VERIFIED", 1).containsEntry("MANUAL_ATTESTED", 1).containsEntry("DEVICE", 2);
        assertThat(r.byState()).containsEntry("EXPIRED", 1);
    }

    @Test
    void emptyWindowGivesZeroes() {
        CaptureKpis.Result r = CaptureKpis.compute(List.of(), List.of());
        assertThat(r.usagePct()).isEqualByComparingTo("0");
        assertThat(r.unmatchedRatePct()).isEqualByComparingTo("0");
    }

    @Test
    void ocrFieldsFromDisplayText() {
        List<OcrFields.Field> f = OcrFields.parse("VITALS", "SYS 128\nDIA: 82\nPUL 71 bpm", new BigDecimal("0.90"));
        assertThat(f).extracting(OcrFields.Field::key).containsExactly("systolic", "diastolic", "pulse");
        assertThat(f.get(0).value()).isEqualByComparingTo("128");
        assertThat(f.get(0).confidence()).isEqualByComparingTo("0.95");
    }

    @Test
    void implausibleOrAmbiguousOcrFieldsAreLeftEmpty() {
        List<OcrFields.Field> f = OcrFields.parse("VITALS", "SYS 999 DIA 80 DIA 85", new BigDecimal("0.90"));
        assertThat(f).hasSize(2);
        assertThat(f.get(0).value()).isNull(); // implausible systolic
        assertThat(f.get(1).value()).isNull(); // two diastolic readings on the photo
        assertThat(OcrFields.parse("SPIROMETRY", "FEV1 3,42 FVC 4.31 FEV1/FVC 0.79", new BigDecimal("0.90")))
                .extracting(OcrFields.Field::key).containsExactly("fev1FvcRatio", "fev1L", "fvcL");
        assertThat(OcrFields.parse("VITALS", "", new BigDecimal("0.9"))).isEmpty();
    }
}
