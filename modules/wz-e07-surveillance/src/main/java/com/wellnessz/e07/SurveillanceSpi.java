package com.wellnessz.e07;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.support.AppointmentStatusListener;
import com.wellnessz.casekernel.support.CapacityDemandSource;
import com.wellnessz.e07.due.DueService;
import com.wellnessz.e07.model.Enrolment;
import com.wellnessz.e07.model.EnrolmentRepository;
import com.wellnessz.e07.model.Programme;
import com.wellnessz.e07.preemployment.PreEmploymentService;
import com.wellnessz.e07.programme.ProgrammeCatalog;
import com.wellnessz.e07.support.SurveillanceSupport;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.security.audit.AuditService;

/**
 * Kernel SPIs towards scheduling (E14): appointment status callbacks for the appointments E07 booked, and clinic demand
 * (enrolments due / overdue by home site × month × service) for capacity planning – counts only.
 */
@Component
public class SurveillanceSpi implements AppointmentStatusListener, CapacityDemandSource {

    static final Set<String> SOURCE_TYPES = Set.of(DueService.SOURCE_TYPE, PreEmploymentService.SOURCE_TYPE, "IMMUNISATION", "CREW_RENEWAL");

    private final DueService due;
    private final PreEmploymentService preEmployment;
    private final EnrolmentRepository enrolments;
    private final ProgrammeCatalog catalog;
    private final SurveillanceSupport support;
    private final AuditService audit;

    public SurveillanceSpi(DueService due, PreEmploymentService preEmployment, EnrolmentRepository enrolments, ProgrammeCatalog catalog,
                           SurveillanceSupport support, AuditService audit) {
        this.due = due;
        this.preEmployment = preEmployment;
        this.enrolments = enrolments;
        this.catalog = catalog;
        this.support = support;
        this.audit = audit;
    }

    @Override
    public boolean supports(String sourceType) {
        return sourceType != null && SOURCE_TYPES.contains(sourceType);
    }

    @Override
    public void appointmentStatusChanged(UUID appointmentId, String employeeId, String sourceType, String sourceRef, Status status) {
        if (sourceType == null || status == null) {
            return;
        }
        switch (sourceType) {
            case DueService.SOURCE_TYPE -> due.appointmentChanged(appointmentId, sourceRef, status);
            case PreEmploymentService.SOURCE_TYPE -> preEmployment.appointmentChanged(appointmentId, sourceRef, status);
            default -> audit.recordAs("system", "MODULE", "SURVEILLANCE_APPOINTMENT_" + status.name(), sourceType,
                    appointmentId == null ? null : appointmentId.toString(), AuditService.Outcome.SUCCESS, null);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<Demand> demand(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            return List.of();
        }
        LocalDate today = support.today();
        Map<String, Programme> programmes = catalog.activeByCode();
        List<Enrolment> active = enrolments.findByActiveTrue();
        Map<String, Employee> emps = support.employeesById(active.stream().map(Enrolment::getEmployeeId).distinct().toList());
        Map<String, String> siteByLocation = new HashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Enrolment e : active) {
            Programme p = programmes.get(e.getProgrammeCode());
            Employee emp = emps.get(e.getEmployeeId());
            if (p == null || emp == null || e.isExempt() && (e.getExemptUntil() == null || !e.getExemptUntil().isBefore(today))) {
                continue;
            }
            LocalDate due = e.getNextDueOn();
            if (due.isAfter(to)) {
                continue;
            }
            // overdue demand is planned in the first month of the window
            LocalDate month = due.isBefore(from) ? from : due;
            String site = siteByLocation.computeIfAbsent(emp.getLocationCode() == null ? "" : emp.getLocationCode(),
                    l -> l.isEmpty() ? "UNASSIGNED" : java.util.Optional.ofNullable(support.homeSite(l)).orElse("UNASSIGNED"));
            counts.merge(site + "|" + p.getServiceCode() + "|" + YearMonth.from(month), 1, Integer::sum);
        }
        List<Demand> out = new ArrayList<>();
        counts.forEach((k, n) -> {
            String[] parts = k.split("\\|", 3);
            out.add(new Demand(parts[0], parts[1], YearMonth.parse(parts[2]), n, "E07"));
        });
        return out;
    }
}
