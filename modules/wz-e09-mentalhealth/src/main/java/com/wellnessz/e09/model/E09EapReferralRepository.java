package com.wellnessz.e09.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E09EapReferralRepository extends JpaRepository<EapReferral, UUID> {

    List<EapReferral> findBySupportCaseIdOrderBySentAtDesc(UUID supportCaseId);

    List<EapReferral> findAllByOrderBySentAtDesc();

    List<EapReferral> findByPartnerCodeOrderBySentAtDesc(String partnerCode);
}
