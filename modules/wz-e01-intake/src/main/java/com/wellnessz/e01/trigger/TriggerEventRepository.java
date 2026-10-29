package com.wellnessz.e01.trigger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TriggerEventRepository extends JpaRepository<TriggerEvent, UUID> {

    List<TriggerEvent> findByReceivedAtBetweenOrderByReceivedAtDesc(Instant from, Instant to);

    List<TriggerEvent> findByEmployeeIdOrderByReceivedAtDesc(String employeeId);
}
