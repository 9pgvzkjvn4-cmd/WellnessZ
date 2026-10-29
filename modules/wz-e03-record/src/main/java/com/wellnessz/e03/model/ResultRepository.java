package com.wellnessz.e03.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ResultRepository extends JpaRepository<Result, UUID> {
    List<Result> findByEmployeeIdOrderByEffectiveAtDesc(String employeeId);

    List<Result> findByOrderId(UUID orderId);

    List<Result> findByEmployeeIdAndCategoryAndBaselineTrue(String employeeId, String category);

    @Query("select r from Result r where r.acknowledgedAt is null and r.flag <> 'NORMAL' order by r.receivedAt asc")
    List<Result> findUnacknowledgedAbnormal();

    @Query("select r from Result r where r.acknowledgedAt is null and r.flag <> 'NORMAL' and r.escalatedAt is null and r.receivedAt < :before")
    List<Result> findOverdueNotEscalated(Instant before);
}
