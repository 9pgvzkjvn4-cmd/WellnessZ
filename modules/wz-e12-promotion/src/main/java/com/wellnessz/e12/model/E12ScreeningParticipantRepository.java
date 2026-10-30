package com.wellnessz.e12.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E12ScreeningParticipantRepository extends JpaRepository<ScreeningParticipant, UUID> {

    List<ScreeningParticipant> findByEventIdOrderByAddedAtAsc(UUID eventId);

    Optional<ScreeningParticipant> findByEventIdAndEmployeeId(UUID eventId, String employeeId);

    List<ScreeningParticipant> findByEmployeeId(String employeeId);
}
