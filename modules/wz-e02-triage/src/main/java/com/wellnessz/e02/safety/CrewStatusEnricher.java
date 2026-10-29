package com.wellnessz.e02.safety;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * SPI: lets a later module (E05 roster-status feed) add roster status, validity dates and operational limitation codes
 * to the crew-scheduling status view without E02 depending on it. Implementations return status-only data.
 */
public interface CrewStatusEnricher {

    record RosterInfo(String rosterStatus, LocalDate validFrom, LocalDate validUntil, List<String> operationalLimitations) {
        public RosterInfo {
            operationalLimitations = operationalLimitations == null ? List.of() : List.copyOf(operationalLimitations);
        }
    }

    /** @return roster information keyed by employee id; employees without data are simply absent */
    Map<String, RosterInfo> rosterInfo(Collection<String> employeeIds);
}
