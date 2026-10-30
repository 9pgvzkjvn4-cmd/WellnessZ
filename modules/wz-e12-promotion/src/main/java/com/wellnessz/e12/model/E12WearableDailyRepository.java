package com.wellnessz.e12.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E12WearableDailyRepository extends JpaRepository<WearableDaily, UUID> {

    Optional<WearableDaily> findByEnrolmentIdAndMetricAndMetricDate(UUID enrolmentId, String metric, LocalDate metricDate);

    List<WearableDaily> findByCampaignIdAndMetric(UUID campaignId, String metric);

    List<WearableDaily> findByEnrolmentId(UUID enrolmentId);

    long deleteByEnrolmentId(UUID enrolmentId);
}
