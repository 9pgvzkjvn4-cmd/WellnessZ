package com.wellnessz.e01.rules;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentEvaluationRepository extends JpaRepository<IncidentEvaluation, UUID> {

    List<IncidentEvaluation> findByOutcomeOrderByEvaluatedAtDesc(IncidentEvaluation.Outcome outcome);

    Optional<IncidentEvaluation> findByCaseId(UUID caseId);
}
