package com.wellnessz.casekernel.device;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Read access to the E14 equipment register for the console (E32-BL-017: migrate the register into the catalogue and
 * fleet inventory; link a device instance to its equipment record so the existing {@code DeviceCalibrationCheck} keeps
 * governing calibration lockout). Implemented by a small adapter in E14. Contract section 13.6.
 */
public interface DeviceEquipmentRegister {

    List<EquipmentItem> list();

    Optional<EquipmentItem> find(String equipmentDeviceId);

    /**
     * @param equipmentDeviceId E14 {@code device_id} – the id the calibration check is keyed on
     * @param status            E14 equipment status (IN_SERVICE, OUT_OF_SERVICE, …)
     */
    record EquipmentItem(String equipmentDeviceId, String assetTag, String category, String model, String serialNumber, String siteCode,
                         String status, LocalDate calibrationDueOn) {
    }

    /** Default when E14 is not deployed. */
    DeviceEquipmentRegister NONE = new DeviceEquipmentRegister() {
        @Override
        public List<EquipmentItem> list() {
            return List.of();
        }

        @Override
        public Optional<EquipmentItem> find(String equipmentDeviceId) {
            return Optional.empty();
        }
    };
}
