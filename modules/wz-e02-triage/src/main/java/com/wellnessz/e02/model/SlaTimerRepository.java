package com.wellnessz.e02.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SlaTimerRepository extends JpaRepository<SlaTimer, UUID> {
    List<SlaTimer> findByCaseIdAndStatusIn(UUID caseId, java.util.Collection<String> statuses);

    List<SlaTimer> findByCaseIdOrderByStartedAtDesc(UUID caseId);

    @Query(value = "select * from triage.sla_timer where status = 'RUNNING' and ((amber_at <= :now and amber_notified = false) or due_at <= :now) order by due_at limit 500 for update skip locked", nativeQuery = true)
    List<SlaTimer> claimDue(Instant now);
}
