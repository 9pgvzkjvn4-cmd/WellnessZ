package com.wellnessz.integration.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /** Claims a batch of due events; SKIP LOCKED lets several replicas relay concurrently without double delivery. */
    @Query(value = """
            select * from platform.outbox_event
             where status in ('PENDING','FAILED') and next_attempt_at <= :now
             order by created_at
             limit :limit
             for update skip locked
            """, nativeQuery = true)
    List<OutboxEvent> claimDue(Instant now, int limit);

    List<OutboxEvent> findByStatusOrderByCreatedAtDesc(OutboxEvent.Status status, Pageable pageable);

    List<OutboxEvent> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
