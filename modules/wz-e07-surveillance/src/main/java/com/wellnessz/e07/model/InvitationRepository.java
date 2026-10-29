package com.wellnessz.e07.model;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InvitationRepository extends JpaRepository<Invitation, UUID> {

    List<Invitation> findByEnrolmentIdOrderBySentAtDesc(UUID enrolmentId);

    List<Invitation> findByEnrolmentIdInOrderBySentAtDesc(Collection<UUID> enrolmentIds);

    List<Invitation> findByStatusAndSentAtBefore(String status, Instant before);

    List<Invitation> findByEmployeeIdInAndSentAtBetween(Collection<String> employeeIds, Instant from, Instant to);

    List<Invitation> findBySentAtBetween(Instant from, Instant to);
}
