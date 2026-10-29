package com.wellnessz.e05.model;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RtwReviewRepository extends JpaRepository<RtwReview, UUID> {

    List<RtwReview> findByPlanIdOrderByDueDateAsc(UUID planId);

    List<RtwReview> findByCompletedAtIsNullAndDueDateLessThanEqualOrderByDueDateAsc(LocalDate date);
}
