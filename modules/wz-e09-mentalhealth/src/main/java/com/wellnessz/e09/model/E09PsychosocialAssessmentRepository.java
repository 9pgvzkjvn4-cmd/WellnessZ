package com.wellnessz.e09.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E09PsychosocialAssessmentRepository extends JpaRepository<PsychosocialAssessment, UUID> {

    List<PsychosocialAssessment> findAllByOrderByAssessedOnDescCreatedAtDesc();

    List<PsychosocialAssessment> findByUnitCodeOrderByAssessedOnDescCreatedAtDesc(String unitCode);
}
