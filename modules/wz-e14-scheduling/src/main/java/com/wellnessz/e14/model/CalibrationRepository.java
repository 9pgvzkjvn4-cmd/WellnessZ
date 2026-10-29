package com.wellnessz.e14.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CalibrationRepository extends JpaRepository<Calibration, UUID> {

    List<Calibration> findByEquipmentIdOrderByPerformedOnDescRecordedAtDesc(UUID equipmentId);
}
