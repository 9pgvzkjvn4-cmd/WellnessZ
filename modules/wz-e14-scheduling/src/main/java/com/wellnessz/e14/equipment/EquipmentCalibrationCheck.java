package com.wellnessz.e14.equipment;

import java.time.LocalDate;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.support.DeviceCalibrationCheck;
import com.wellnessz.e14.engine.EquipmentRules;
import com.wellnessz.e14.model.Equipment;
import com.wellnessz.e14.model.EquipmentRepository;

/**
 * Kernel SPI consulted by E03 device result capture (E14-HLR-04): a registered device that is out of service or past its
 * calibration due date blocks result recording; devices not in the register are UNKNOWN.
 */
@Component
public class EquipmentCalibrationCheck implements DeviceCalibrationCheck {

    private final EquipmentRepository equipment;

    public EquipmentCalibrationCheck(EquipmentRepository equipment) {
        this.equipment = equipment;
    }

    @Override
    @Transactional(readOnly = true)
    public Status check(String deviceId, LocalDate on) {
        if (deviceId == null || deviceId.isBlank()) {
            return new Status(Verdict.UNKNOWN, null, null);
        }
        Equipment e = equipment.findByDeviceId(deviceId.strip()).orElse(null);
        if (e == null) {
            return new Status(Verdict.UNKNOWN, null, null);
        }
        Verdict v = EquipmentRules.verdict(true, e.getStatus(), e.getCalibrationDueOn(), on);
        return new Status(v, e.getLastCertificateNumber(), e.getCalibrationDueOn());
    }
}
