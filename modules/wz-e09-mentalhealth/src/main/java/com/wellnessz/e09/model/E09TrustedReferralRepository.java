package com.wellnessz.e09.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E09TrustedReferralRepository extends JpaRepository<TrustedReferral, UUID> {

    List<TrustedReferral> findAllByOrderByReferredAtDesc();

    boolean existsByPeerContactId(UUID peerContactId);
}
