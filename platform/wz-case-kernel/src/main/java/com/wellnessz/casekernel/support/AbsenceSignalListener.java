package com.wellnessz.casekernel.support;

import java.time.LocalDate;
import java.util.UUID;

/**
 * SPI notified by absence management (E06) when an absence crosses the long-term threshold or ends after a long-term
 * absence, e.g. so that surveillance (E07-BL-020) can require a return-after-serious-illness medical before RTW for
 * safety-critical roles. Identifiers and dates only – never the absence reason.
 */
public interface AbsenceSignalListener {

    /** An absence episode reached {@code days} calendar days (called once, when the long-term threshold is crossed). */
    void longTermAbsence(String employeeId, UUID caseId, LocalDate startDate, long days);
}
