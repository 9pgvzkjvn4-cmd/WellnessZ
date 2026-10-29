package com.wellnessz.e08.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E08DisclosureSetRepository extends JpaRepository<DisclosureSet, UUID> {

    List<DisclosureSet> findByClaimIdOrderByCreatedAtAsc(UUID claimId);

    List<DisclosureSet> findAllByOrderByCreatedAtDesc();
}
