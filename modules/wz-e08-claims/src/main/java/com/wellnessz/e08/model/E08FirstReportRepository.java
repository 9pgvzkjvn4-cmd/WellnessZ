package com.wellnessz.e08.model;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E08FirstReportRepository extends JpaRepository<FirstReport, UUID> {

    List<FirstReport> findByClaimIdOrderByCreatedAtAsc(UUID claimId);

    List<FirstReport> findAllByOrderByDueAtAsc();

    List<FirstReport> findByStatusInAndDueAtBeforeOrderByDueAtAsc(Collection<String> statuses, Instant before);

    Optional<FirstReport> findByReportRef(String reportRef);
}
