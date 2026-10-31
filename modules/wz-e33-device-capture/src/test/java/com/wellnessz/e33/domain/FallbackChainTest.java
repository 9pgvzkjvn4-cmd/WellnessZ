package com.wellnessz.e33.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.wellnessz.common.error.ApiException;

class FallbackChainTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 31);
    static final List<FallbackChain.Alternate> ALT = List.of(new FallbackChain.Alternate("DEV-000004", "Vital signs monitor – Room 1"));

    @Test
    void chainOrderIsAlternateThenOcrThenManual() {
        List<FallbackChain.Option> o = FallbackChain.offer(ALT, true, false, false);
        assertThat(o).extracting(FallbackChain.Option::path).containsExactly("ALTERNATE_DEVICE", "OCR_VERIFIED", "MANUAL_ATTESTED");
        assertThat(o).extracting(FallbackChain.Option::rank).containsExactly(1, 2, 3);
    }

    @Test
    void noOcrWithoutPackage() {
        assertThat(FallbackChain.offer(List.of(), false, false, false)).extracting(FallbackChain.Option::path).containsExactly("MANUAL_ATTESTED");
    }

    @Test
    void restrictedHasManualOnlyForCollector() {
        assertThat(FallbackChain.offer(List.of(), true, true, false)).isEmpty();
        assertThat(FallbackChain.offer(List.of(), true, true, true)).extracting(FallbackChain.Option::path).containsExactly("MANUAL_ATTESTED");
    }

    @Test
    void manualNeedsCompleteAttestation() {
        List<FallbackChain.Option> offered = FallbackChain.offer(List.of(), false, false, false);
        Map<String, Object> values = Map.of("systolic", 128);
        assertThatThrownBy(() -> FallbackChain.validate("MANUAL_ATTESTED", "DEVICE_UNAVAILABLE", null, offered, values, null,
                new FallbackChain.Attestation("Connex", "SN1", null, TODAY), TODAY))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).code()).isEqualTo("attestation_incomplete");
        assertThatThrownBy(() -> FallbackChain.validate("MANUAL_ATTESTED", "DEVICE_UNAVAILABLE", null, offered, values, null,
                new FallbackChain.Attestation("Connex", "SN1", "CAL-1", TODAY.plusDays(1)), TODAY))
                .extracting(e -> ((ApiException) e).code()).isEqualTo("attestation_incomplete");
        assertThat(FallbackChain.validate("MANUAL_ATTESTED", "DEVICE_UNAVAILABLE", null, offered, values, null,
                new FallbackChain.Attestation("Connex", "SN1", "CAL-1", TODAY), TODAY)).isEqualTo("MANUAL_ATTESTED");
    }

    @Test
    void ocrNeedsThePhotoAndValues() {
        List<FallbackChain.Option> offered = FallbackChain.offer(List.of(), true, false, false);
        assertThatThrownBy(() -> FallbackChain.validate("OCR_VERIFIED", "DISPLAY_ONLY", null, offered, Map.of("fev1L", 3.1), null, null, TODAY))
                .extracting(e -> ((ApiException) e).code()).isEqualTo("image_required");
        assertThatThrownBy(() -> FallbackChain.validate("OCR_VERIFIED", "DISPLAY_ONLY", null, offered, Map.of(), UUID.randomUUID(), null, TODAY))
                .extracting(e -> ((ApiException) e).code()).isEqualTo("validation_failed");
        assertThat(FallbackChain.validate("OCR_VERIFIED", "DISPLAY_ONLY", null, offered, Map.of("fev1L", 3.1), UUID.randomUUID(), null, TODAY))
                .isEqualTo("OCR_VERIFIED");
    }

    @Test
    void alternateMustBeOffered() {
        List<FallbackChain.Option> offered = FallbackChain.offer(ALT, false, false, false);
        assertThat(FallbackChain.validate("ALTERNATE_DEVICE", "DEVICE_LOCKED", "DEV-000004", offered, null, null, null, TODAY))
                .isEqualTo("ALTERNATE_DEVICE");
        assertThatThrownBy(() -> FallbackChain.validate("ALTERNATE_DEVICE", "DEVICE_LOCKED", "DEV-999999", offered, null, null, null, TODAY))
                .extracting(e -> ((ApiException) e).code()).isEqualTo("fallback_not_offered");
        assertThatThrownBy(() -> FallbackChain.validate("OCR_VERIFIED", "DEVICE_LOCKED", null, offered, Map.of("x", 1), UUID.randomUUID(), null, TODAY))
                .extracting(e -> ((ApiException) e).code()).isEqualTo("fallback_not_offered");
    }

    @Test
    void reasonCodeIsMandatory() {
        assertThatThrownBy(() -> FallbackChain.validate("MANUAL_ATTESTED", "BROKEN", null, List.of(), Map.of(), null, null, TODAY))
                .extracting(e -> ((ApiException) e).code()).isEqualTo("validation_failed");
    }
}
