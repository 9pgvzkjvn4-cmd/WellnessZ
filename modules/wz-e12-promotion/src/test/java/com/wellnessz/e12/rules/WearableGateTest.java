package com.wellnessz.e12.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class WearableGateTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 30);

    static String check(boolean enrolled, boolean consent, String metric, LocalDate date, String value) {
        return WearableGate.check(enrolled, consent, metric, date, value == null ? null : new BigDecimal(value), TODAY, 31);
    }

    @Test
    void acceptsDailyAggregatesWithEnrolmentAndConsent() {
        assertThat(check(true, true, "STEPS", TODAY.minusDays(1), "8400")).isNull();
        assertThat(check(true, true, "SLEEP_MINUTES", TODAY, "420")).isNull();
        assertThat(check(true, true, "ACTIVE_MINUTES", TODAY.minusDays(31), "45.0")).isNull();
    }

    @Test
    void consentGate() {
        assertThat(check(false, true, "STEPS", TODAY, "100")).isEqualTo("NOT_ENROLLED");
        assertThat(check(true, false, "STEPS", TODAY, "100")).isEqualTo("NO_CONSENT");
        assertThat(check(false, false, "STEPS", TODAY, "100")).isEqualTo("NOT_ENROLLED");
    }

    @Test
    void dataMinimisation() {
        assertThat(check(true, true, "HEART_RATE", TODAY, "70")).isEqualTo("UNKNOWN_METRIC");
        assertThat(check(true, true, "STEPS", TODAY.plusDays(1), "100")).isEqualTo("DATE_OUT_OF_RANGE");
        assertThat(check(true, true, "STEPS", TODAY.minusDays(32), "100")).isEqualTo("DATE_OUT_OF_RANGE");
        assertThat(check(true, true, "STEPS", TODAY, "-1")).isEqualTo("VALUE_OUT_OF_RANGE");
        assertThat(check(true, true, "SLEEP_MINUTES", TODAY, "1441")).isEqualTo("VALUE_OUT_OF_RANGE");
        assertThat(check(true, true, "STEPS", TODAY, "100.5")).isEqualTo("VALUE_OUT_OF_RANGE");
        assertThat(check(true, true, "STEPS", TODAY, null)).isEqualTo("VALUE_OUT_OF_RANGE");
    }
}
