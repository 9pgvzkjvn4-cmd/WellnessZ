package com.wellnessz.e33.model;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E33CaptureObservationRepository extends JpaRepository<CaptureObservation, UUID> {

    Optional<CaptureObservation> findByObservationId(UUID observationId);

    List<CaptureObservation> findBySessionIdOrderByReceivedAtAsc(UUID sessionId);

    List<CaptureObservation> findBySessionIdIn(Collection<UUID> sessionIds);

    List<CaptureObservation> findByEmployeeIdAndCategoryAndDecisionInOrderByEffectiveAtAsc(String employeeId, String category,
                                                                                           List<String> decisions);
}
