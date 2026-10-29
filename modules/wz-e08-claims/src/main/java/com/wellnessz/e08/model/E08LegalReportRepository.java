package com.wellnessz.e08.model;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E08LegalReportRepository extends JpaRepository<LegalReport, UUID> {

    List<LegalReport> findByRequestIdOrderByVersionAsc(UUID requestId);

    List<LegalReport> findByRequestIdInOrderByVersionAsc(Collection<UUID> requestIds);

    Optional<LegalReport> findByRequestIdAndVersion(UUID requestId, int version);
}
