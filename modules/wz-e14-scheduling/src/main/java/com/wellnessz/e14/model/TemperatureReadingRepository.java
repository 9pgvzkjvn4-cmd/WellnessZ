package com.wellnessz.e14.model;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TemperatureReadingRepository extends JpaRepository<TemperatureReading, UUID> {

    @Query("select r from TemperatureReading r where r.equipmentId = ?1 and r.recordedAt >= ?2 and r.recordedAt < ?3 order by r.recordedAt")
    List<TemperatureReading> findBetween(UUID equipmentId, Instant from, Instant to);

    Optional<TemperatureReading> findFirstByEquipmentIdOrderByRecordedAtDesc(UUID equipmentId);
}
