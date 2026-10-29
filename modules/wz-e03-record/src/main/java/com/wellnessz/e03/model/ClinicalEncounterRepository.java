package com.wellnessz.e03.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ClinicalEncounterRepository extends JpaRepository<ClinicalEncounter, UUID> {
    List<ClinicalEncounter> findByEmployeeIdOrderByCreatedAtDesc(String employeeId);

    List<ClinicalEncounter> findByCaseIdOrderByCreatedAtDesc(UUID caseId);

    List<ClinicalEncounter> findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(String status, Instant before);
}
