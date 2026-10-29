package com.wellnessz.e06.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AbsenceFeedMismatchRepository extends JpaRepository<FeedMismatch, UUID> {

    List<FeedMismatch> findByStatusOrderByCreatedAtDesc(String status);

    List<FeedMismatch> findAllByOrderByCreatedAtDesc();

    Optional<FeedMismatch> findFirstBySourceSystemAndSourceRefAndStatus(String sourceSystem, String sourceRef, String status);
}
