package com.wellnessz.e08.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E08LegalRequestRepository extends JpaRepository<LegalRequest, UUID> {

    List<LegalRequest> findAllByOrderByCreatedAtDesc();

    List<LegalRequest> findByClaimIdOrderByCreatedAtAsc(UUID claimId);
}
