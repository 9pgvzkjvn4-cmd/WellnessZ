package com.wellnessz.casekernel.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseTaskRepository extends JpaRepository<CaseTask, UUID> {

    List<CaseTask> findByCaseIdOrderByCreatedAtAsc(UUID caseId);

    List<CaseTask> findByCaseIdAndStatus(UUID caseId, CaseTask.Status status);

    long countByCaseIdAndStatus(UUID caseId, CaseTask.Status status);
}
