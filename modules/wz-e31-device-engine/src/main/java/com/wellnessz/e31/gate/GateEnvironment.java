package com.wellnessz.e31.gate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.wellnessz.casekernel.device.DeviceCatalogueLookup;
import com.wellnessz.casekernel.support.DeviceCalibrationCheck;

/**
 * What the gates need from outside the message: the E14 calibration verdicts, E32 QC status, the employee's history and
 * consent. The production implementation uses the kernel SPIs and the engine store; simulators and unit tests use their own.
 */
public interface GateEnvironment {

    /** Verdicts of every {@link DeviceCalibrationCheck} bean (as E03 does); empty when none is deployed. */
    List<DeviceCalibrationCheck.Status> calibration(String equipmentDeviceId, LocalDate on);

    DeviceCatalogueLookup.QcStatus qc(String deviceInstanceId, LocalDate on, String lot);

    /** The employee's last {@code limit} values of a component key in a category (routed observations, newest first). */
    List<BigDecimal> history(String employeeId, String category, String key, int limit);

    boolean consent(String employeeId, String purpose);

    boolean requireRegisteredDevices();

    /** Simulator / dry-run environment: calibrated, QC not required, no history, consent given. */
    GateEnvironment SIMULATOR = new GateEnvironment() {
        @Override
        public List<DeviceCalibrationCheck.Status> calibration(String equipmentDeviceId, LocalDate on) {
            return List.of(new DeviceCalibrationCheck.Status(DeviceCalibrationCheck.Verdict.VALID, "SIM-CAL", on.plusYears(1)));
        }

        @Override
        public DeviceCatalogueLookup.QcStatus qc(String deviceInstanceId, LocalDate on, String lot) {
            return new DeviceCatalogueLookup.QcStatus("NOT_REQUIRED", null, null, null, lot, null);
        }

        @Override
        public List<BigDecimal> history(String employeeId, String category, String key, int limit) {
            return List.of();
        }

        @Override
        public boolean consent(String employeeId, String purpose) {
            return true;
        }

        @Override
        public boolean requireRegisteredDevices() {
            return false;
        }
    };
}
