package com.wellnessz.casekernel.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseEventRepository extends JpaRepository<CaseEvent, UUID> {

    List<CaseEvent> findByCaseIdOrderByOccurredAtAsc(UUID caseId);
}
