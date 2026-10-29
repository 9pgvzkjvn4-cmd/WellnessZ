package com.wellnessz.e07.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PreEmploymentRepository extends JpaRepository<PreEmployment, UUID> {

    Optional<PreEmployment> findByOfferRef(String offerRef);

    List<PreEmployment> findByCandidateIdOrderByCreatedAtDesc(String candidateId);

    List<PreEmployment> findByStatusOrderByCreatedAtDesc(String status);

    List<PreEmployment> findAllByOrderByCreatedAtDesc(Pageable page);

    List<PreEmployment> findByRetentionUntilBeforeAndPurgedAtIsNull(LocalDate date);
}
