package com.wellnessz.e14.device;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.device.DeviceEquipmentRegister;
import com.wellnessz.e14.model.Equipment;
import com.wellnessz.e14.model.EquipmentRepository;

/**
 * Kernel {@link DeviceEquipmentRegister} adapter (increment 6, contract §3 / §13.6): read-only view of the E14 equipment
 * register for the device console (E32) – migration of the register into the fleet inventory (E32-BL-017) and the link of
 * a device instance to the equipment record whose calibration ({@code DeviceCalibrationCheck}) keeps governing lockout.
 * Depends on the kernel only; E14 never sees a device-module class.
 */
@Component
public class EquipmentRegisterAdapter implements DeviceEquipmentRegister {

    private final EquipmentRepository equipment;

    public EquipmentRegisterAdapter(EquipmentRepository equipment) {
        this.equipment = equipment;
    }

    @Override
    @Transactional(readOnly = true)
    public List<EquipmentItem> list() {
        return equipment.search(null, null).stream().filter(e -> !"VACCINE_FRIDGE".equals(e.getCategory())).map(EquipmentRegisterAdapter::item).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<EquipmentItem> find(String equipmentDeviceId) {
        if (equipmentDeviceId == null || equipmentDeviceId.isBlank()) {
            return Optional.empty();
        }
        return equipment.findByDeviceId(equipmentDeviceId.strip()).map(EquipmentRegisterAdapter::item);
    }

    static EquipmentItem item(Equipment e) {
        return new EquipmentItem(e.getDeviceId(), e.getAssetTag(), e.getCategory(), e.getModel(), e.getSerialNumber(), e.getSiteCode(), e.getStatus(),
                e.getCalibrationDueOn());
    }
}
