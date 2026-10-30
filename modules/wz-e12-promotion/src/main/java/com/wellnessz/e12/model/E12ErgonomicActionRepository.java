package com.wellnessz.e12.model;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E12ErgonomicActionRepository extends JpaRepository<ErgonomicAction, UUID> {

    List<ErgonomicAction> findAllByOrderByDueDateAsc();

    List<ErgonomicAction> findByAssessmentIdOrderByDueDateAsc(UUID assessmentId);

    List<ErgonomicAction> findByUnitCodeInOrderByDueDateAsc(Collection<String> unitCodes);

    List<ErgonomicAction> findByStatusAndDueDateLessThanEqualOrderByDueDateAsc(String status, LocalDate dueBy);
}
