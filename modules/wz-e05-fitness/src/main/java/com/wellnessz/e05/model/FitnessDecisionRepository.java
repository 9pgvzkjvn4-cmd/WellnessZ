package com.wellnessz.e05.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FitnessDecisionRepository extends JpaRepository<FitnessDecisionEntity, UUID> {

    List<FitnessDecisionEntity> findByCaseIdOrderByDecidedAtDesc(UUID caseId);

    List<FitnessDecisionEntity> findByEmployeeIdOrderByDecidedAtDesc(String employeeId);

    List<FitnessDecisionEntity> findByStatusOrderByDecidedAtAsc(String status);
}
