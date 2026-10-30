package com.wellnessz.e10.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E10RtdPlanRepository extends JpaRepository<RtdPlan, UUID> {

    List<RtdPlan> findAllByOrderByCreatedAtDesc();

    List<RtdPlan> findByStatusOrderByCreatedAtDesc(String status);

    Optional<RtdPlan> findFirstByTestId(UUID testId);

    Optional<RtdPlan> findFirstByRtdTestId(UUID rtdTestId);

    List<RtdPlan> findByStatusIn(java.util.Collection<String> statuses);
}
