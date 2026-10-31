package com.wellnessz.casekernel.device;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Worklist entries for association priority 1 and demographic / order push to devices (E31-HLR-05, E31-HLR-08):
 * booked appointments (E14) and open device orders (E03 lab orders of category AUDIOMETRY / SPIROMETRY / VISION / ECG).
 * Implemented by small adapters in E14 and E03; several implementations are merged by the engine. Contract section 9.
 */
public interface DeviceWorklistSource {

    List<WorklistEntry> worklist(String siteCode, LocalDate day, DeviceCodes.Category category);

    /**
     * @param orderRef    E03 order id or E14 appointment id (prefixed {@code ORD:} / {@code APT:})
     * @param badgeId     employee badge (for badge association at the device)
     * @param dateOfBirth only for push to vendor software that requires it (minimisation: omitted otherwise)
     */
    record WorklistEntry(String orderRef, String employeeId, String displayName, String badgeId, LocalDate dateOfBirth, String sex,
                         DeviceCodes.Category category, String siteCode, Instant scheduledAt, java.util.UUID caseId) {
    }
}
