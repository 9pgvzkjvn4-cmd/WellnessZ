package com.wellnessz.e04.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TreatmentPlanRepository extends JpaRepository<TreatmentPlan, UUID> {

    List<TreatmentPlan> findByCaseIdOrderByCreatedAtDesc(UUID caseId);

    List<TreatmentPlan> findByEmployeeIdOrderByCreatedAtDesc(String employeeId);

    List<TreatmentPlan> findByStatus(String status);

    List<TreatmentPlan> findBySeriesIdOrderByVersionAsc(UUID seriesId);

    boolean existsByCaseId(UUID caseId);
}
