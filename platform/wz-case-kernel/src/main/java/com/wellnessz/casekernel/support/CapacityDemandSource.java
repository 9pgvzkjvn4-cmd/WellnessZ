package com.wellnessz.casekernel.support;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * SPI through which modules report forecast clinic demand (E14-F05 capacity planning): surveillance due lists (E07),
 * absence contact schedules (E06), RTW reviews. Counts only – never person-level data.
 */
public interface CapacityDemandSource {

    record Demand(String siteCode, String serviceCode, YearMonth month, int appointments, String source) {
    }

    List<Demand> demand(LocalDate from, LocalDate to);
}
