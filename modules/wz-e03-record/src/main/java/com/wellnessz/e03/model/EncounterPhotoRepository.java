package com.wellnessz.e03.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EncounterPhotoRepository extends JpaRepository<EncounterPhoto, UUID> {
    List<EncounterPhoto> findByEncounterIdOrderByTakenAtAsc(UUID encounterId);
}
