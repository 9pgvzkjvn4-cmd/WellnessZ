package com.wellnessz.e09.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E09MhFitnessAssessmentRepository extends JpaRepository<MhFitnessAssessment, UUID> {

    List<MhFitnessAssessment> findByEmployeeIdOrderByAssessedAtDesc(String employeeId);
}
