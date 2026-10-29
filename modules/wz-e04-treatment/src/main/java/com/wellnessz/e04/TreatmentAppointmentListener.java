package com.wellnessz.e04;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.support.AppointmentStatusListener;
import com.wellnessz.e04.plan.PlanService;
import com.wellnessz.e04.telehealth.TelehealthService;

/** Scheduling (E14) callbacks for the appointments E04 booked: treatment follow-ups and telehealth sessions. */
@Component
public class TreatmentAppointmentListener implements AppointmentStatusListener {

    private final PlanService plans;
    private final TelehealthService telehealth;

    public TreatmentAppointmentListener(PlanService plans, TelehealthService telehealth) {
        this.plans = plans;
        this.telehealth = telehealth;
    }

    @Override
    public boolean supports(String sourceType) {
        return PlanService.SOURCE_TYPE.equals(sourceType) || TelehealthService.SOURCE_TYPE.equals(sourceType);
    }

    @Override
    public void appointmentStatusChanged(UUID appointmentId, String employeeId, String sourceType, String sourceRef, Status status) {
        if (status == null) {
            return;
        }
        if (PlanService.SOURCE_TYPE.equals(sourceType)) {
            plans.appointmentChanged(appointmentId, sourceRef, status);
        } else if (TelehealthService.SOURCE_TYPE.equals(sourceType)) {
            telehealth.appointmentChanged(sourceRef, status);
        }
    }
}
