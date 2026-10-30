package com.wellnessz.e12.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E12WearableEnrolmentRepository extends JpaRepository<WearableEnrolment, UUID> {

    List<WearableEnrolment> findByEmployeeIdOrderByEnrolledAtDesc(String employeeId);

    Optional<WearableEnrolment> findByEmployeeIdAndCampaignIdAndStatus(String employeeId, UUID campaignId, String status);

    List<WearableEnrolment> findByConsentIdAndStatus(UUID consentId, String status);
}
