package com.wellnessz.casekernel.support;

import java.util.UUID;

/**
 * SPI notified synchronously (inside the recording transaction) when a clinical result is recorded – LIS, device or
 * manual – so that surveillance (E07-F07) can evaluate it against its programme standards and raise follow-up. Payload is
 * identifiers and codes only.
 */
public interface ResultFollowUpListener {

    record RecordedResult(UUID resultId, String employeeId, UUID caseId, String category, String codeSystem, String code,
                          String flag, boolean baseline, String recordedBy) {
    }

    void resultRecorded(RecordedResult result);
}
