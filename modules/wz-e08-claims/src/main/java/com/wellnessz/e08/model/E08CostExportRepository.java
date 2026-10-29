package com.wellnessz.e08.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E08CostExportRepository extends JpaRepository<CostExport, UUID> {

    List<CostExport> findAllByOrderByCreatedAtDesc();

    List<CostExport> findByExportMonthOrderByCreatedAtDesc(String exportMonth);
}
