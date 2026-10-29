package com.wellnessz.e03.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EncounterObservationRepository extends JpaRepository<EncounterObservation, UUID> {
    List<EncounterObservation> findByEncounterIdAndActiveTrueOrderByPositionAsc(UUID encounterId);
}
