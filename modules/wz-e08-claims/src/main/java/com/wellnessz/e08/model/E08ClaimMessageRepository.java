package com.wellnessz.e08.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E08ClaimMessageRepository extends JpaRepository<ClaimMessage, UUID> {

    List<ClaimMessage> findByClaimIdOrderBySentAtAsc(UUID claimId);
}
