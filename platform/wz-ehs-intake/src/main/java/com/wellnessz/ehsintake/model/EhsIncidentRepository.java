package com.wellnessz.ehsintake.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EhsIncidentRepository extends JpaRepository<EhsIncident, UUID> {

    boolean existsBySourceSystemAndExternalRef(String sourceSystem, String externalRef);

    List<EhsIncident> findAllByOrderByReceivedAtDesc(Pageable pageable);
}
