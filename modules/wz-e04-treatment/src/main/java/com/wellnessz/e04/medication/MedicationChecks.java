package com.wellnessz.e04.medication;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.e04.model.StockBatch;

/** Pre-administration safety checks: stock batch (known, in date, in stock) and direct allergy matches. */
public final class MedicationChecks {

    private MedicationChecks() {
    }

    /** Recorded allergy reduced to its substance display and code. */
    public record Substance(String display, String code) {
    }

    /** {@code batch_unknown}, {@code batch_expired}, {@code out_of_stock} or {@code null} when the batch can be used. */
    public static String batchProblem(StockBatch b, LocalDate today, boolean stockTracked) {
        if (b == null) {
            return "batch_unknown";
        }
        if (b.getExpiry().isBefore(today)) {
            return "batch_expired";
        }
        if (stockTracked && b.getQuantity() < 1) {
            return "out_of_stock";
        }
        return null;
    }

    /**
     * Direct matches between the item and recorded allergies: same SNOMED code, or a substance whose name contains the
     * item's generic name (or vice versa). Drug-class cross-reactivity is added by E03 clinical decision support.
     */
    public static List<String> allergyAlerts(String itemName, String snomedCode, List<Substance> allergies) {
        Set<String> out = new LinkedHashSet<>();
        String generic = itemName == null ? "" : itemName.toLowerCase(Locale.ROOT).split("[\\s/,(]")[0];
        for (Substance s : allergies) {
            String display = s.display() == null ? "" : s.display().toLowerCase(Locale.ROOT);
            boolean code = snomedCode != null && snomedCode.equals(s.code());
            boolean name = generic.length() >= 4 && (display.contains(generic) || !display.isBlank() && generic.contains(display.replace("allergy to ", "").trim()));
            if (code || name) {
                out.add("Recorded allergy (" + (s.display() == null ? s.code() : s.display()) + ") matches " + itemName);
            }
        }
        return new ArrayList<>(out);
    }

    /** 422 {@code allergy_alert} carrying the alert texts (OH clinicians only see this response). */
    public static class AllergyAlertException extends ApiException {
        private static final long serialVersionUID = 1L;
        private final List<String> alerts;

        public AllergyAlertException(List<String> alerts) {
            super(HttpStatus.UNPROCESSABLE_CONTENT, "allergy_alert", "Allergy alert: " + String.join("; ", alerts)
                    + ". Record an override reason to administer anyway.");
            this.alerts = List.copyOf(alerts);
        }

        @Override
        public Map<String, Object> properties() {
            return Map.of("alerts", alerts);
        }
    }
}
