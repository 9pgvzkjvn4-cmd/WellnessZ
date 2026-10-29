package com.wellnessz.e05.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DutyProposalRepository extends JpaRepository<DutyProposal, UUID> {

    List<DutyProposal> findByCaseIdOrderByProposedAtAsc(UUID caseId);

    List<DutyProposal> findByStatusOrderByProposedAtAsc(String status);
}
