package com.wellnessz.e04.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MedicationAdministrationRepository extends JpaRepository<MedicationAdministration, UUID> {

    List<MedicationAdministration> findByCaseIdOrderByAdministeredAtDesc(UUID caseId);

    List<MedicationAdministration> findByAdministeredAtBetween(Instant from, Instant to);
}
