package com.wellnessz.e11.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E11InvitationRepository extends JpaRepository<Invitation, UUID> {

    List<Invitation> findByCampaignId(UUID campaignId);

    Optional<Invitation> findByCampaignIdAndEmployeeId(UUID campaignId, String employeeId);

    List<Invitation> findByEmployeeId(String employeeId);
}
