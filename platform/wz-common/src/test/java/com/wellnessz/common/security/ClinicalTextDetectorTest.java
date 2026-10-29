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
    }

    @Test
    void allowsWorkImpactFacts() {
        assertThat(ClinicalTextDetector.looksClinical("Cannot lift trolleys above 10 kg since Monday; moved to galley prep")).isFalse();
        assertThat(ClinicalTextDetector.looksClinical("Missed two early shifts; safety concern on the ramp")).isFalse();
    }
}
