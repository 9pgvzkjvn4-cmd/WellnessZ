package com.wellnessz.e04.model;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TreatmentReferralRepository extends JpaRepository<Referral, UUID> {

    List<Referral> findByCaseIdOrderByCreatedAtDesc(UUID caseId);

    List<Referral> findByEmployeeIdOrderByCreatedAtDesc(String employeeId);

    List<Referral> findAllByOrderByCreatedAtDesc(Pageable page);

    List<Referral> findByStatusOrderByCreatedAtDesc(String status, Pageable page);

    List<Referral> findByProviderCodeAndStatusInOrderByCreatedAtDesc(String providerCode, Collection<String> statuses, Pageable page);

    List<Referral> findByStatusInAndReportDueAtBefore(Collection<String> statuses, Instant before);

    List<Referral> findBySentAtBetween(Instant from, Instant to);

    Optional<Referral> findByRef(String ref);

    boolean existsByCaseId(UUID caseId);
}
