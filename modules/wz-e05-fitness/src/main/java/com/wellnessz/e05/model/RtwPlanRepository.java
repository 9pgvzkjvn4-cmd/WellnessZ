package com.wellnessz.e05.model;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RtwPlanRepository extends JpaRepository<RtwPlan, UUID> {

    List<RtwPlan> findByCaseIdOrderByCreatedAtAsc(UUID caseId);

    List<RtwPlan> findByEmployeeIdAndStatusInOrderByCreatedAtDesc(String employeeId, Collection<String> statuses);

    List<RtwPlan> findByStatusInOrderByCreatedAtDesc(Collection<String> statuses);
}
