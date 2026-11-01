package com.wellnessz.e03.device;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceWorklistSource;
import com.wellnessz.e03.model.LabOrder;
import com.wellnessz.e03.model.LabOrderRepository;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.masterdata.EmployeeRepository;

/**
 * {@link DeviceWorklistSource} over open E03 device orders (categories AUDIOMETRY / SPIROMETRY / VISION / ECG and the other
 * device categories) for association priority 1 and worklist push (E31-HLR-05 / -08). Order references are {@code ORD:<id>};
 * orders carry no site, so every open order up to the end of the day is offered. Dates of birth are not held in the HR
 * master and are never returned.
 */
@Component
public class OrderWorklistSource implements DeviceWorklistSource {

    private final LabOrderRepository orders;
    private final EmployeeRepository employees;

    public OrderWorklistSource(LabOrderRepository orders, EmployeeRepository employees) {
        this.orders = orders;
        this.employees = employees;
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorklistEntry> worklist(String siteCode, LocalDate day, DeviceCodes.Category category) {
        if (category == null || !ResultDeviceSink.CATEGORIES.contains(category)) {
            return List.of();
        }
        List<WorklistEntry> out = new ArrayList<>();
        for (LabOrder o : orders.findOpenByCategory(Set.of(category.name()), day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant())) {
            Employee e = employees.findById(o.getEmployeeId()).orElse(null);
            if (e == null || !e.isActive()) {
                continue;
            }
            out.add(new WorklistEntry("ORD:" + o.getId(), e.getEmployeeId(), e.getDisplayName(), e.getBadgeId(), null, null, category,
                    siteCode, o.getOrderedAt(), o.getCaseId()));
        }
        return out;
    }
}
