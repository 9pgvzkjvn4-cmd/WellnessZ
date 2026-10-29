package com.wellnessz.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.wellnessz.common.text.ClinicalTextDetector;

class ClinicalTextDetectorTest {

    @Test
    void detectsDiagnosisTypeContent() {
        assertThat(ClinicalTextDetector.looksClinical("I think she has depression")).isTrue();
        assertThat(ClinicalTextDetector.looksClinical("Possible fracture of the wrist")).isTrue();
        assertThat(ClinicalTextDetector.looksClinical("Coded as F32.1 by GP")).isTrue();
        assertThat(ClinicalTextDetector.looksClinical("Lumbar disc prolapse – avoid lifting")).isTrue();
        assertThat(ClinicalTextDetector.looksClinical("Off with norovirus, attending physiotherapy")).isTrue();
    }

    @Test
    void allowsWorkImpactFacts() {
        assertThat(ClinicalTextDetector.looksClinical("Cannot lift trolleys above 10 kg since Monday; moved to galley prep")).isFalse();
        assertThat(ClinicalTextDetector.looksClinical("Missed two early shifts; safety concern on the ramp")).isFalse();
        assertThat(ClinicalTextDetector.looksClinical("No lifting above 10 kg; no airside driving; maximum 6 hours per day")).isFalse();
        assertThat(ClinicalTextDetector.looksClinical("Week 1-2: 4 hours per day, tray-set packing, seated; week 3-4: 6 hours")).isFalse();
    }
}
