package com.wellnessz.e04.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TreatmentReferralHistoryRepository extends JpaRepository<ReferralHistory, UUID> {

    List<ReferralHistory> findByReferralIdOrderByAtAsc(UUID referralId);
}
