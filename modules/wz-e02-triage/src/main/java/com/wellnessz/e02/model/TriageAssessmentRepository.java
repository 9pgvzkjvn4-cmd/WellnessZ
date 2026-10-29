package com.wellnessz.e02.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TriageAssessmentRepository extends JpaRepository<TriageAssessment, UUID> {
    List<TriageAssessment> findByCaseIdOrderByAssessedAtDesc(UUID caseId);

    List<TriageAssessment> findByAssessedAtBetween(Instant from, Instant to);
}
