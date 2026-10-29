package com.wellnessz.e06.model;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AbsenceQueueItemRepository extends JpaRepository<QueueItem, UUID> {

    List<QueueItem> findAllByOrderByRaisedAtDesc();

    List<QueueItem> findByStatusInOrderByRaisedAtAsc(Collection<String> statuses);

    List<QueueItem> findByEmployeeIdOrderByRaisedAtDesc(String employeeId);

    Optional<QueueItem> findFirstByEmployeeIdAndActionAndStatusNot(String employeeId, String action, String status);

    List<QueueItem> findByFirstContactAtBetween(Instant from, Instant to);

    List<QueueItem> findByRaisedAtBetween(Instant from, Instant to);
}
