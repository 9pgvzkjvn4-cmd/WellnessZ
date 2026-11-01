package com.wellnessz.e14.device;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceWorklistSource;
import com.wellnessz.e14.model.Appointment;
import com.wellnessz.e14.model.AppointmentRepository;
import com.wellnessz.e14.model.AppointmentType;
import com.wellnessz.e14.model.AppointmentTypeRepository;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.masterdata.EmployeeRepository;

/**
 * {@link DeviceWorklistSource} over booked E14 appointments whose type needs a device (audiometer, spirometer, vision screener,
 * ECG) at a site on a day – association priority 1 and worklist push (E31-HLR-05 / -08). References are {@code APT:<id>}.
 * Scheduling holds no clinical content and no date of birth; only the minimum is returned.
 */
@Component
public class AppointmentWorklistSource implements DeviceWorklistSource {

    static final Map<String, DeviceCodes.Category> DEVICE_CATEGORY = Map.of("AUDIOMETER", DeviceCodes.Category.AUDIOMETRY,
            "SPIROMETER", DeviceCodes.Category.SPIROMETRY, "VISION_SCREENER", DeviceCodes.Category.VISION, "ECG", DeviceCodes.Category.ECG);

    private final AppointmentRepository appointments;
    private final AppointmentTypeRepository types;
    private final EmployeeRepository employees;

    public AppointmentWorklistSource(AppointmentRepository appointments, AppointmentTypeRepository types, EmployeeRepository employees) {
        this.appointments = appointments;
        this.types = types;
        this.employees = employees;
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorklistEntry> worklist(String siteCode, LocalDate day, DeviceCodes.Category category) {
        if (siteCode == null || day == null || category == null) {
            return List.of();
        }
        Instant from = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        List<WorklistEntry> out = new ArrayList<>();
        for (Appointment a : appointments.findAtSite(siteCode, from, from.plusSeconds(86_400), PageRequest.of(0, 500))) {
            if (!AppointmentRepository.OPEN.contains(a.getStatus())) {
                continue;
            }
            AppointmentType t = types.findById(a.getAppointmentType()).orElse(null);
            if (t == null || t.getDeviceCategory() == null || DEVICE_CATEGORY.get(t.getDeviceCategory()) != category) {
                continue;
            }
            Employee e = employees.findById(a.getEmployeeId()).orElse(null);
            if (e == null || !e.isActive()) {
                continue;
            }
            out.add(new WorklistEntry("APT:" + a.getId(), e.getEmployeeId(), e.getDisplayName(), e.getBadgeId(), null, null, category, siteCode,
                    a.getStartsAt(), a.getCaseId()));
        }
        return out;
    }
}
