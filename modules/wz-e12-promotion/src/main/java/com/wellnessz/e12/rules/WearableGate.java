package com.wellnessz.e12.rules;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * Data-minimisation and consent gate for wearable daily aggregates (E12-BL-005): a value is accepted only for a known
 * metric, for a past day inside the accepted window, within a plausible range, and only while the employee has an active
 * enrolment in the campaign whose WEARABLE_DATA consent is still granted. Pure – unit tested.
 */
public final class WearableGate {

    /** Upper bound of a plausible daily value per metric. */
    public static final Map<String, BigDecimal> MAX = Map.of("STEPS", BigDecimal.valueOf(100_000), "SLEEP_MINUTES", BigDecimal.valueOf(1_440),
            "ACTIVE_MINUTES", BigDecimal.valueOf(1_440));

    private WearableGate() {
    }

    /** @return {@code null} when accepted, otherwise the rejection reason code */
    public static String check(boolean enrolmentActive, boolean consentActive, String metric, LocalDate date, BigDecimal value, LocalDate today,
                               int maxAgeDays) {
        if (!enrolmentActive) {
            return "NOT_ENROLLED";
        }
        if (!consentActive) {
            return "NO_CONSENT";
        }
        BigDecimal max = metric == null ? null : MAX.get(metric);
        if (max == null) {
            return "UNKNOWN_METRIC";
        }
        if (date == null || date.isAfter(today) || date.isBefore(today.minusDays(maxAgeDays))) {
            return "DATE_OUT_OF_RANGE";
        }
        if (value == null || value.signum() < 0 || value.compareTo(max) > 0 || value.scale() > 0 && value.stripTrailingZeros().scale() > 0) {
            return "VALUE_OUT_OF_RANGE";
        }
        return null;
    }
}
