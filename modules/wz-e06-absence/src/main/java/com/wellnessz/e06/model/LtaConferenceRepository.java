package com.wellnessz.e06.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LtaConferenceRepository extends JpaRepository<LtaConference, UUID> {

    List<LtaConference> findByCaseIdOrderByScheduledForAsc(UUID caseId);
}
