package com.wellnessz.ehsintake.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EhsIncidentRepository extends JpaRepository<EhsIncident, UUID> {

    boolean existsBySourceSystemAndExternalRef(String sourceSystem, String externalRef);

    /** Multi-casualty incidents carry one row per injured person (E01-BL-020). */
    @org.springframework.data.jpa.repository.Query("""
            select count(i) > 0 from EhsIncident i where i.sourceSystem = ?1 and i.externalRef = ?2
               and coalesce(i.employeeId, '') = coalesce(?3, '')""")
    boolean existsBySourceSystemAndExternalRefAndEmployeeId(String sourceSystem, String externalRef, String employeeId);

    List<EhsIncident> findByExternalRefOrderByReceivedAt(String externalRef);

    List<EhsIncident> findTop200ByEmployeeIdOrderByReceivedAtDesc(String employeeId);

    List<EhsIncident> findAllByOrderByReceivedAtDesc(Pageable pageable);
}
