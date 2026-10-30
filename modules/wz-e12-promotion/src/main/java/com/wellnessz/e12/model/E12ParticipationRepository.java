package com.wellnessz.e12.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E12ParticipationRepository extends JpaRepository<Participation, UUID> {

    List<Participation> findByCampaignId(UUID campaignId);

    List<Participation> findByEmployeeId(String employeeId);

    boolean existsByCampaignIdAndEmployeeIdAndActivityAndOccurredAt(UUID campaignId, String employeeId, String activity, Instant occurredAt);

    long deleteByCampaignIdAndEmployeeIdAndSource(UUID campaignId, String employeeId, String source);
}
