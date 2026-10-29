package com.wellnessz.e05.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IhrAssessmentRepository extends JpaRepository<IhrAssessment, UUID> {

    List<IhrAssessment> findByCaseIdOrderByAssessedAtDesc(UUID caseId);
}
