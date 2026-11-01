package com.wellnessz.e31.gate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.device.DeviceCatalogueLookup;
import com.wellnessz.casekernel.device.DeviceSpis;
import com.wellnessz.casekernel.support.DeviceCalibrationCheck;
import com.wellnessz.e31.store.ObservationStore;
import com.wellnessz.e31.support.EngineSettings;
import com.wellnessz.security.consent.ConsentPurpose;
import com.wellnessz.security.consent.ConsentService;

/**
 * Production gate environment: every {@link DeviceCalibrationCheck} bean (E14 {@code EquipmentCalibrationCheck}, exactly as
 * E03 does), the E32 QC status through {@link DeviceCatalogueLookup#qcStatus}, the engine's own observation history and
 * the platform consent register.
 */
@Component("e31EngineGateEnvironment")
public class EngineGateEnvironment implements GateEnvironment {

    private final List<DeviceCalibrationCheck> calibration;
    private final DeviceSpis spis;
    private final ObservationStore observations;
    private final ConsentService consents;
    private final EngineSettings settings;

    public EngineGateEnvironment(List<DeviceCalibrationCheck> calibration, DeviceSpis spis, ObservationStore observations, ConsentService consents,
                                 EngineSettings settings) {
        this.calibration = List.copyOf(calibration);
        this.spis = spis;
        this.observations = observations;
        this.consents = consents;
        this.settings = settings;
    }

    @Override
    public List<DeviceCalibrationCheck.Status> calibration(String equipmentDeviceId, LocalDate on) {
        List<DeviceCalibrationCheck.Status> out = new ArrayList<>();
        if (equipmentDeviceId == null || equipmentDeviceId.isBlank()) {
            return out;
        }
        for (DeviceCalibrationCheck c : calibration) {
            out.add(c.check(equipmentDeviceId, on));
        }
        return out;
    }

    @Override
    public DeviceCatalogueLookup.QcStatus qc(String deviceInstanceId, LocalDate on, String lot) {
        DeviceCatalogueLookup.QcStatus s = spis.catalogue().qcStatus(deviceInstanceId, on, lot);
        return s == null ? DeviceCatalogueLookup.QcStatus.unknown() : s;
    }

    @Override
    public List<BigDecimal> history(String employeeId, String category, String key, int limit) {
        return observations.history(employeeId, category, key, limit);
    }

    @Override
    public boolean consent(String employeeId, String purpose) {
        return consents.hasActiveConsent(employeeId, ConsentPurpose.valueOf(purpose));
    }

    @Override
    public boolean requireRegisteredDevices() {
        return settings.requireRegisteredDevices();
    }
}
