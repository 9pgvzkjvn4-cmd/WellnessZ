package com.wellnessz.e05.model;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface JobOfferRepository extends JpaRepository<JobOffer, UUID> {

    List<JobOffer> findByCaseIdOrderByIssuedAtDesc(UUID caseId);

    List<JobOffer> findByEmployeeIdOrderByIssuedAtDesc(String employeeId);

    List<JobOffer> findByStatusAndRespondByBefore(String status, LocalDate date);
}
