package com.wellnessz.casekernel.support;

import java.time.LocalDate;

/**
 * SPI implemented by the equipment register (E14-F04) and consulted by result capture (E03 device results): a device
 * that is out of calibration or out of service must not be used to record results (E14-HLR-04).
 */
public interface DeviceCalibrationCheck {

    enum Verdict {
        /** Device not in the register – accepted unless the register is configured to require registration. */
        UNKNOWN,
        VALID,
        OUT_OF_CALIBRATION,
        OUT_OF_SERVICE
    }

    record Status(Verdict verdict, String calibrationId, LocalDate calibrationDueOn) {
    }

    Status check(String deviceId, LocalDate on);
}
