package com.wellnessz.casekernel.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OhCaseRepository extends JpaRepository<OhCase, UUID> {

    List<OhCase> findByEmployeeIdOrderByCreatedAtDesc(String employeeId);

    @Query("select c from OhCase c where c.orgUnitPath = ?1 or c.orgUnitPath like concat(?1, '/%') order by c.updatedAt desc")
    List<OhCase> findInOrgScope(String scopePath, Pageable pageable);

    List<OhCase> findAllByOrderByUpdatedAtDesc(Pageable pageable);

    Optional<OhCase> findBySourceAndSourceRef(CaseSource source, String sourceRef);

    @Query(value = "select nextval('casemgmt.case_ref_seq')", nativeQuery = true)
    long nextCaseNumber();
}
