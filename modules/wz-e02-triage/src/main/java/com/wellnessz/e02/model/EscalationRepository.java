package com.wellnessz.e02.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EscalationRepository extends JpaRepository<Escalation, UUID> {
    List<Escalation> findByCaseIdOrderByRequestedAtDesc(UUID caseId);

    List<Escalation> findByStatusInOrderByDueAtAsc(java.util.Collection<String> statuses);

    long countByCaseIdAndStatusIn(UUID caseId, java.util.Collection<String> statuses);
}
