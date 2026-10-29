package com.wellnessz.e04.model;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VarianceAlertRepository extends JpaRepository<VarianceAlert, UUID> {

    List<VarianceAlert> findByStatusOrderByRaisedAtDesc(String status, Pageable page);

    List<VarianceAlert> findAllByOrderByRaisedAtDesc(Pageable page);

    List<VarianceAlert> findByPlanIdOrderByRaisedAtDesc(UUID planId);

    boolean existsByPlanIdAndStatusIn(UUID planId, Collection<String> statuses);
}
