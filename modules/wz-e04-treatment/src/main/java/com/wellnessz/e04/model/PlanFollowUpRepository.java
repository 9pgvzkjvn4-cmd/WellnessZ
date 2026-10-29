package com.wellnessz.e04.model;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanFollowUpRepository extends JpaRepository<PlanFollowUp, UUID> {

    List<PlanFollowUp> findByPlanIdOrderByDueDateAsc(UUID planId);

    List<PlanFollowUp> findByPlanIdInOrderByDueDateAsc(Collection<UUID> planIds);

    List<PlanFollowUp> findByStatusInAndDueDateBetween(Collection<String> statuses, LocalDate from, LocalDate to);

    List<PlanFollowUp> findByAppointmentId(UUID appointmentId);
}
