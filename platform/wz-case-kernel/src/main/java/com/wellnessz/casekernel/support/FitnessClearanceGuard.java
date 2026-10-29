package com.wellnessz.casekernel.support;

import java.util.Optional;

/**
 * SPI consulted before a fitness decision that returns someone to work (FIT / FIT_WITH_LIMITATIONS) is recorded, e.g.
 * "a return-after-serious-illness medical is required before RTW for safety-critical roles" (E07-BL-020).
 */
public interface FitnessClearanceGuard {

    /**
     * @param employeeId the employee
     * @param outcome    the E05 fitness outcome being recorded
     * @return a user-facing reason (no clinical content) when the decision must not be recorded yet
     */
    Optional<String> blockingReason(String employeeId, String outcome);
}
