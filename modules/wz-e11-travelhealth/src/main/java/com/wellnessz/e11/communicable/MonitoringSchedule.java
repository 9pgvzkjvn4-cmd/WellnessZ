package com.wellnessz.e11.communicable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Contact monitoring (E11-F02, pure): one daily check-in for each of the condition's monitoring days starting on the day the
 * contact is notified; a check-in is symptomatic when any symptom other than {@code NONE} is reported or the temperature
 * reaches the fever threshold.
 */
public final class MonitoringSchedule {

    public static final Set<String> SYMPTOMS = Set.of("FEVER", "COUGH", "VOMITING", "DIARRHOEA", "RASH", "SORE_THROAT", "NONE");

    private MonitoringSchedule() {
    }

    public static List<LocalDate> checkInDays(LocalDate start, int monitoringDays) {
        List<LocalDate> out = new ArrayList<>();
        for (int i = 0; i < monitoringDays; i++) {
            out.add(start.plusDays(i));
        }
        return out;
    }

    /** Last monitoring day, or {@code null} when the condition needs no monitoring. */
    public static LocalDate monitoringUntil(LocalDate start, int monitoringDays) {
        return monitoringDays <= 0 ? null : start.plusDays(monitoringDays - 1L);
    }

    /** Validates a symptom list: known codes only, {@code NONE} on its own. */
    public static boolean validSymptoms(Collection<String> symptoms) {
        if (symptoms == null || symptoms.isEmpty() || !SYMPTOMS.containsAll(symptoms)) {
            return false;
        }
        return !symptoms.contains("NONE") || symptoms.size() == 1;
    }

    public static boolean symptomatic(Collection<String> symptoms, BigDecimal temperatureC, BigDecimal feverThreshold) {
        boolean anySymptom = symptoms != null && symptoms.stream().anyMatch(s -> !"NONE".equals(s));
        boolean fever = temperatureC != null && feverThreshold != null && temperatureC.compareTo(feverThreshold) >= 0;
        return anySymptom || fever;
    }
}
