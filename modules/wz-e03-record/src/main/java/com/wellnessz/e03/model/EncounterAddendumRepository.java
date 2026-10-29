package com.wellnessz.e03.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EncounterAddendumRepository extends JpaRepository<EncounterAddendum, UUID> {
    List<EncounterAddendum> findByEncounterIdOrderBySignedAtAsc(UUID encounterId);
}
