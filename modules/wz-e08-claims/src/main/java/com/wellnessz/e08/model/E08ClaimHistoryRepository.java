package com.wellnessz.e08.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E08ClaimHistoryRepository extends JpaRepository<ClaimHistory, UUID> {

    List<ClaimHistory> findByClaimIdOrderByOccurredAtAsc(UUID claimId);
}
