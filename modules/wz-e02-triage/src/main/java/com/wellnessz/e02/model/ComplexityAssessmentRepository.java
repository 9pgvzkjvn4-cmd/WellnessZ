package com.wellnessz.e02.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ComplexityAssessmentRepository extends JpaRepository<ComplexityAssessment, UUID> {
    List<ComplexityAssessment> findByCalculatedAtBetween(Instant from, Instant to);

    java.util.Optional<ComplexityAssessment> findFirstByCaseIdOrderByCalculatedAtDesc(UUID caseId);
}
