package com.wellnessz.e06.model;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AdviceReportRepository extends JpaRepository<AdviceReport, UUID> {

    List<AdviceReport> findByRequestIdOrderByVersionDesc(UUID requestId);

    Optional<AdviceReport> findFirstByRequestIdOrderByVersionDesc(UUID requestId);

    List<AdviceReport> findByRequestIdInOrderByVersionDesc(Collection<UUID> requestIds);
}
