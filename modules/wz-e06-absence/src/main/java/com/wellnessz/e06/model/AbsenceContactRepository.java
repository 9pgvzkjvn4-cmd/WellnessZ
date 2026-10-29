package com.wellnessz.e06.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AbsenceContactRepository extends JpaRepository<Contact, UUID> {

    List<Contact> findByCaseIdOrderByContactedAtDesc(UUID caseId);

    List<Contact> findByQueueItemIdOrderByContactedAtDesc(UUID queueItemId);

    List<Contact> findByEmployeeIdAndContactedAtGreaterThanEqualOrderByContactedAtAsc(String employeeId, Instant since);
}
