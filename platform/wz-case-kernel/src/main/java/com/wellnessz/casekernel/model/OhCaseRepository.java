package com.wellnessz.casekernel.model;

import java.time.Instant;
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

    List<OhCase> findByIncidentRefOrderByCreatedAt(String incidentRef);

    /** Duplicate-detection candidates (E01-F06): same employee, still open, created in the window. */
    @Query("""
            select c from OhCase c where c.employeeId = ?1 and c.id <> ?2
               and c.status not in (com.wellnessz.casekernel.model.CaseStatus.CLOSED, com.wellnessz.casekernel.model.CaseStatus.MERGED)
               and c.createdAt >= ?3 order by c.createdAt desc""")
    List<OhCase> findOpenForEmployeeSince(String employeeId, UUID excludeId, Instant since);

    @Query("select count(c) from OhCase c where c.employeeId = ?1 and c.bodyPart = ?2 and c.createdAt >= ?3")
    long countByEmployeeAndBodyPartSince(String employeeId, String bodyPart, Instant since);

    /** Status-only lookups for crew scheduling / hygiene (latest case per employee is resolved in the service). */
    @Query("select c from OhCase c where c.employeeId in ?1 order by c.updatedAt desc")
    List<OhCase> findByEmployeeIds(java.util.Collection<String> employeeIds);

    @Query("select c from OhCase c where c.safetyCategory = ?1 and c.orgUnitCode = ?2 order by c.updatedAt desc")
    List<OhCase> findBySafetyCategoryAndDepartment(SafetyCategory category, String departmentCode);
}
