package com.wellnessz.e06.model;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LtaMilestoneRepository extends JpaRepository<LtaMilestone, UUID> {

    List<LtaMilestone> findByCaseIdOrderByDueDateAscWeekAsc(UUID caseId);

    List<LtaMilestone> findByStatusAndDueDateLessThanOrderByDueDateAsc(String status, LocalDate date);

    List<LtaMilestone> findByStatusInAndDueDateBetween(Collection<String> statuses, LocalDate from, LocalDate to);

    Optional<LtaMilestone> findFirstByAppointmentId(UUID appointmentId);
}
