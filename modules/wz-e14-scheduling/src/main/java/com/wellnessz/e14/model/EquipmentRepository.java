package com.wellnessz.e14.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EquipmentRepository extends JpaRepository<Equipment, UUID> {

    Optional<Equipment> findByDeviceId(String deviceId);

    Optional<Equipment> findByAssetTag(String assetTag);

    @Query("select e from Equipment e where (?1 is null or e.siteCode = ?1) and (?2 is null or e.category = ?2) order by e.siteCode, e.assetTag")
    List<Equipment> search(String siteCode, String category);

    @Query("""
            select e from Equipment e where e.status <> 'RETIRED' and e.calibrationDueOn is not null and e.calibrationDueOn <= ?1
             order by e.calibrationDueOn, e.assetTag""")
    List<Equipment> findCalibrationDueBy(LocalDate date);

    List<Equipment> findBySiteCodeAndCategoryAndStatus(String siteCode, String category, String status);
}
