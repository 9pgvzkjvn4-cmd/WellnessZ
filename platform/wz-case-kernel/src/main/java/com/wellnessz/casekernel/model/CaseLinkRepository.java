package com.wellnessz.casekernel.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseLinkRepository extends JpaRepository<CaseLink, UUID> {

    List<CaseLink> findByCaseIdOrderByCreatedAt(UUID caseId);

    List<CaseLink> findByTypeAndRef(CaseLink.Type type, String ref);
}
