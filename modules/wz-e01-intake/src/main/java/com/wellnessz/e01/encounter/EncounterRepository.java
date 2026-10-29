package com.wellnessz.e01.encounter;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EncounterRepository extends JpaRepository<Encounter, UUID> {

    Optional<Encounter> findByClientRef(UUID clientRef);

    List<Encounter> findTop200ByStatusInOrderByOccurredAtDesc(List<Encounter.Status> statuses);

    List<Encounter> findByEmployeeIdOrderByOccurredAtDesc(String employeeId);
}
