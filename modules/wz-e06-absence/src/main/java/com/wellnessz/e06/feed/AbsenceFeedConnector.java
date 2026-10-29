package com.wellnessz.e06.feed;

import java.time.LocalDate;
import java.util.List;

/**
 * SPI for the daily absence pull (E06-F01) from HRIS / time-and-attendance / crew-management systems. The default
 * implementation is a no-op; site-specific adapters register further beans. Rows go through the same reconciliation as the
 * push endpoint.
 */
public interface AbsenceFeedConnector {

    /** {@code HRIS}, {@code TNA}, {@code CREW} or {@code MANUAL}. */
    String sourceSystem();

    /** Episodes created or changed since {@code since}. */
    List<FeedRow> pull(LocalDate since);

    /** One absence row as delivered by a source system. */
    record FeedRow(String sourceRef, String employeeRef, LocalDate startDate, LocalDate endDate, String absenceType, String reasonCategory,
                   boolean certified) {
    }
}
