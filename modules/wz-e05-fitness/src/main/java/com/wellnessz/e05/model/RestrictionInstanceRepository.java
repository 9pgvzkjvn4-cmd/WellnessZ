package com.wellnessz.e05.model;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RestrictionInstanceRepository extends JpaRepository<RestrictionInstance, UUID> {

    List<RestrictionInstance> findByCaseIdOrderByCreatedAtAsc(UUID caseId);

    List<RestrictionInstance> findByEmployeeIdOrderByValidUntilAsc(String employeeId);

    List<RestrictionInstance> findByStatusInOrderByValidUntilAsc(Collection<String> statuses);

    @Query("select r from RestrictionInstance r where r.status = 'ACTIVE' and r.validUntil is not null and r.validUntil <= ?1 order by r.validUntil")
    List<RestrictionInstance> findActiveEndingOnOrBefore(LocalDate date);
}
