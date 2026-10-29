package com.wellnessz.security.disclosure;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DisclosureRepository extends JpaRepository<DisclosureRecord, UUID> {

    List<DisclosureRecord> findByEmployeeIdOrderByDisclosedAtDesc(String employeeId);

    List<DisclosureRecord> findByConsentIdAndRevokedAtIsNull(UUID consentId);

    List<DisclosureRecord> findByResourceTypeAndResourceIdAndRevokedAtIsNull(String resourceType, String resourceId);
}
