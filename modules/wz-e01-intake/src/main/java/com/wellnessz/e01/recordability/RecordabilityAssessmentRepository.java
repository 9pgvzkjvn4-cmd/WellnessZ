package com.wellnessz.e01.recordability;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RecordabilityAssessmentRepository extends JpaRepository<RecordabilityAssessment, UUID> {

    List<RecordabilityAssessment> findByCaseIdOrderByVersionDesc(UUID caseId);

    Optional<RecordabilityAssessment> findFirstByCaseIdOrderByVersionDesc(UUID caseId);
}
