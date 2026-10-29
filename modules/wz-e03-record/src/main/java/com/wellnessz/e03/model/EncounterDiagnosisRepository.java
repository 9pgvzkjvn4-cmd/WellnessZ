package com.wellnessz.e03.model;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EncounterDiagnosisRepository extends JpaRepository<EncounterDiagnosis, UUID> {
    List<EncounterDiagnosis> findByEncounterIdAndActiveTrueOrderByPositionAsc(UUID encounterId);

    List<EncounterDiagnosis> findByEncounterIdInAndActiveTrue(Collection<UUID> encounterIds);
}
