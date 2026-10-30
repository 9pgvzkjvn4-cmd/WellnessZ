package com.wellnessz.e12.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E12CampaignInvitationRepository extends JpaRepository<CampaignInvitation, UUID> {

    List<CampaignInvitation> findByCampaignId(UUID campaignId);

    List<CampaignInvitation> findByEmployeeIdOrderByInvitedAtDesc(String employeeId);

    boolean existsByCampaignIdAndEmployeeId(UUID campaignId, String employeeId);
}
