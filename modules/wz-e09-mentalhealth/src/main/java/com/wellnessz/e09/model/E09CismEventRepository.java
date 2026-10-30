package com.wellnessz.e09.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E09CismEventRepository extends JpaRepository<CismEvent, UUID> {

    List<CismEvent> findAllByOrderByOccurredAtDesc();

    Optional<CismEvent> findByIncidentId(String incidentId);
}
