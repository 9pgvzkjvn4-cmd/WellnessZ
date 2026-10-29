package com.wellnessz.casekernel.support;

import java.util.List;
import java.util.Map;

import com.wellnessz.security.identity.CurrentUser;

/**
 * SPI for the employee OH chart (E03-F01): business modules contribute named sections and alerts to the chart summary
 * without the record module depending on them (e.g. E05 contributes "fitness" and "restrictions").
 */
public interface ChartContributor {

    record Alert(String kind, String severity, String text) {
    }

    record Contribution(Map<String, Object> sections, List<Alert> alerts) {
        public static final Contribution EMPTY = new Contribution(Map.of(), List.of());
    }

    Contribution contribute(CurrentUser user, String employeeId);
}
