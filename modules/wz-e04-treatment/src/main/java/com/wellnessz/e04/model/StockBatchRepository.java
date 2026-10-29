package com.wellnessz.e04.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StockBatchRepository extends JpaRepository<StockBatch, UUID> {

    List<StockBatch> findBySiteCodeOrderByItemCodeAscExpiryAsc(String siteCode);

    List<StockBatch> findAllByOrderByItemCodeAscExpiryAsc();

    Optional<StockBatch> findByItemCodeAndBatchNumberAndSiteCode(String itemCode, String batchNumber, String siteCode);
}
