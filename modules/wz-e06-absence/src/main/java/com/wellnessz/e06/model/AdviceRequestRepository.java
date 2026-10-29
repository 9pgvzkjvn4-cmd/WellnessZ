package com.wellnessz.e06.model;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AdviceRequestRepository extends JpaRepository<AdviceRequest, UUID> {

    List<AdviceRequest> findAllByOrderByRequestedAtDesc();

    List<AdviceRequest> findByStatusOrderByRequestedAtAsc(String status);

    List<AdviceRequest> findByEmployeeIdOrderByRequestedAtDesc(String employeeId);

    List<AdviceRequest> findByStatusInAndSlaDueAtBetween(Collection<String> statuses, Instant from, Instant to);
}
