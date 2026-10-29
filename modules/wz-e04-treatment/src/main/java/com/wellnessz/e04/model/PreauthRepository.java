package com.wellnessz.e04.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PreauthRepository extends JpaRepository<Preauth, UUID> {

    List<Preauth> findByCaseIdOrderByCreatedAtDesc(UUID caseId);

    List<Preauth> findAllByOrderByCreatedAtDesc(Pageable page);

    List<Preauth> findByStatusOrderByCreatedAtDesc(String status, Pageable page);

    List<Preauth> findByPayerCodeAndStatusInOrderByCreatedAtDesc(String payerCode, Collection<String> statuses, Pageable page);

    Optional<Preauth> findByRef(String ref);

    List<Preauth> findByStatusInAndValidUntilBefore(Collection<String> statuses, LocalDate date);

    List<Preauth> findBySubmittedAtBetween(Instant from, Instant to);
}
