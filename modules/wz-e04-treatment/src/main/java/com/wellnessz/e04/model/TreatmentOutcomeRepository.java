package com.wellnessz.e04.model;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TreatmentOutcomeRepository extends JpaRepository<TreatmentOutcome, UUID> {

    List<TreatmentOutcome> findByCaseIdOrderByRecordedAtAsc(UUID caseId);

    boolean existsByCaseIdAndMilestone(UUID caseId, String milestone);

    List<TreatmentOutcome> findByCaseIdIn(Collection<UUID> caseIds);
}
