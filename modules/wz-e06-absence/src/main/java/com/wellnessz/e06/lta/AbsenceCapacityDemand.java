package com.wellnessz.e06.lta;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.support.AppointmentStatusListener;
import com.wellnessz.casekernel.support.CapacityDemandSource;
import com.wellnessz.e06.model.AbsenceEnums.AdviceStatus;
import com.wellnessz.e06.model.AbsenceEnums.MilestoneStatus;
import com.wellnessz.e06.model.AbsenceEnums.MilestoneType;
import com.wellnessz.e06.model.AdviceRequest;
import com.wellnessz.e06.model.AdviceRequestRepository;
import com.wellnessz.e06.model.LtaMilestone;
import com.wellnessz.e06.model.LtaMilestoneRepository;
import com.wellnessz.e06.model.LtaPathway;
import com.wellnessz.e06.model.LtaPathwayRepository;
import com.wellnessz.e06.support.AbsenceSupport;

/**
 * E06 clinic demand for capacity planning (counts only): open long-term absence contact / advice-report milestones and open
 * management advice requests per home site and month, service {@code ABSENCE_REVIEW}. Also receives the status callbacks
 * of absence-contact appointments booked through the scheduling SPI.
 */
@Component
public class AbsenceCapacityDemand implements CapacityDemandSource, AppointmentStatusListener {

    public static final String SERVICE = "ABSENCE_REVIEW";
    static final Set<String> OPEN = Set.of(MilestoneStatus.PENDING.name(), MilestoneStatus.OVERDUE.name());
    static final Set<String> CLINIC_MILESTONES = Set.of(MilestoneType.CONTACT.name(), MilestoneType.ADVICE_REPORT.name(),
            MilestoneType.RTW_REVIEW.name());
    static final Set<String> OPEN_ADVICE = Set.of(AdviceStatus.SUBMITTED.name(), AdviceStatus.ACCEPTED.name(), AdviceStatus.REPORT_DRAFTED.name());

    private final LtaMilestoneRepository milestones;
    private final LtaPathwayRepository pathways;
    private final AdviceRequestRepository advice;
    private final LtaService lta;
    private final AbsenceSupport support;

    public AbsenceCapacityDemand(LtaMilestoneRepository milestones, LtaPathwayRepository pathways, AdviceRequestRepository advice, LtaService lta,
                                 AbsenceSupport support) {
        this.milestones = milestones;
        this.pathways = pathways;
        this.advice = advice;
        this.lta = lta;
        this.support = support;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Demand> demand(LocalDate from, LocalDate to) {
        Map<String, Integer> counts = new TreeMap<>();
        for (LtaMilestone m : milestones.findByStatusInAndDueDateBetween(OPEN, LocalDate.of(2000, 1, 1), to)) {
            if (!CLINIC_MILESTONES.contains(m.getMilestoneType())) {
                continue;
            }
            LtaPathway p = pathways.findById(m.getCaseId()).orElse(null);
            if (p == null || !LtaService.ACTIVE.equals(p.getStatus())) {
                continue;
            }
            LocalDate due = m.getDueDate().isBefore(from) ? from : m.getDueDate();
            add(counts, p.getEmployeeId(), YearMonth.from(due));
        }
        for (AdviceRequest r : advice.findByStatusInAndSlaDueAtBetween(OPEN_ADVICE, LocalDate.of(2000, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC),
                to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC))) {
            LocalDate due = LocalDate.ofInstant(r.getSlaDueAt(), ZoneOffset.UTC);
            add(counts, r.getEmployeeId(), YearMonth.from(due.isBefore(from) ? from : due));
        }
        List<Demand> out = new ArrayList<>();
        counts.forEach((k, n) -> {
            String[] parts = k.split("\\|");
            out.add(new Demand(parts[0], SERVICE, YearMonth.parse(parts[1]), n, "E06"));
        });
        return out;
    }

    private void add(Map<String, Integer> counts, String employeeId, YearMonth month) {
        String site = support.employee(employeeId).flatMap(support::homeSite).orElse("HQ-MC");
        counts.merge(site + "|" + month, 1, Integer::sum);
    }

    @Override
    public boolean supports(String sourceType) {
        return LtaService.CONTACT_SOURCE.equals(sourceType);
    }

    @Override
    public void appointmentStatusChanged(java.util.UUID appointmentId, String employeeId, String sourceType, String sourceRef, Status status) {
        lta.appointmentStatus(appointmentId, sourceRef == null ? "" : sourceRef, status.name());
    }
}
