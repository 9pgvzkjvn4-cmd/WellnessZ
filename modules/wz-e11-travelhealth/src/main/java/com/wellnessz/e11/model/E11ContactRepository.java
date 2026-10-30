package com.wellnessz.e11.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E11ContactRepository extends JpaRepository<Contact, UUID> {

    List<Contact> findByCdCaseIdOrderByRiskLevelAscOverlapMinutesDesc(UUID cdCaseId);

    Optional<Contact> findByCdCaseIdAndEmployeeId(UUID cdCaseId, String employeeId);

    long countByCdCaseId(UUID cdCaseId);

    long countByCdCaseIdAndNotifiedAtIsNotNull(UUID cdCaseId);
}
