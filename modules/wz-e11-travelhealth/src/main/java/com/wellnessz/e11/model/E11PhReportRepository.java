package com.wellnessz.e11.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E11PhReportRepository extends JpaRepository<PhReport, UUID> {

    List<PhReport> findTop500ByOrderByDueAtAsc();

    List<PhReport> findByStatus(String status);

    List<PhReport> findByCdCaseIdAndAuthorityCode(UUID cdCaseId, String authorityCode);

    List<PhReport> findByExposureIdAndAuthorityCode(UUID exposureId, String authorityCode);
}
