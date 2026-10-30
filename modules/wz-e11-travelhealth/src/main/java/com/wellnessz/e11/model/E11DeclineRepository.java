package com.wellnessz.e11.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E11DeclineRepository extends JpaRepository<Decline, UUID> {

    List<Decline> findByCampaignId(UUID campaignId);

    boolean existsByCampaignIdAndEmployeeId(UUID campaignId, String employeeId);
}
