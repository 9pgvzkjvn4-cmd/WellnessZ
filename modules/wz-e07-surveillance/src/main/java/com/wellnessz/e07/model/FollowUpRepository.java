package com.wellnessz.e07.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FollowUpRepository extends JpaRepository<FollowUp, UUID> {

    List<FollowUp> findAllByOrderByCreatedAtDesc(Pageable page);

    List<FollowUp> findByStatusOrderByCreatedAtDesc(String status, Pageable page);

    boolean existsByResultIdAndRuleCode(UUID resultId, String ruleCode);

    List<FollowUp> findByProgrammeCodeAndCreatedAtBetween(String programmeCode, Instant from, Instant to);
}
