package com.wellnessz.e10.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E10TestRepository extends JpaRepository<DaTest, UUID> {

    Optional<DaTest> findBySourceRef(String sourceRef);

    List<DaTest> findByStatusInOrderByDueAtAsc(Collection<String> statuses);

    List<DaTest> findByEmployeeIdOrderByCreatedAtDesc(String employeeId);

    List<DaTest> findBySelectionIdOrderByCreatedAtAsc(UUID selectionId);

    List<DaTest> findByRtdPlanIdOrderByScheduledForAsc(UUID rtdPlanId);

    List<DaTest> findByRequestedByOrderByCreatedAtDesc(String requestedBy);

    List<DaTest> findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(Instant from, Instant to);

    List<DaTest> findByStatusAndScheduledForLessThanEqual(String status, LocalDate date);
}
