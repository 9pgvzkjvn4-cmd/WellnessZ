package com.wellnessz.e02.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CaseAssignmentRepository extends JpaRepository<CaseAssignment, UUID> {
    List<CaseAssignment> findByCaseIdOrderByAssignedAtDesc(UUID caseId);
}
