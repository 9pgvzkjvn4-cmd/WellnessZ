package com.wellnessz.e33.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E33DeviceConsentRepository extends JpaRepository<DeviceConsent, UUID> {

    List<DeviceConsent> findByEmployeeIdOrderByGrantedAtDesc(String employeeId);

    List<DeviceConsent> findByConsentIdAndStatus(UUID consentId, String status);
}
