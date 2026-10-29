package com.wellnessz.e08.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E08ClaimTaskRepository extends JpaRepository<ClaimTask, UUID> {

    List<ClaimTask> findByClaimIdOrderByDueDateAscCreatedAtAsc(UUID claimId);

    long countByClaimIdAndStatus(UUID claimId, String status);
}
