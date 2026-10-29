package com.wellnessz.e07.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IhMeasurementRepository extends JpaRepository<IhMeasurement, UUID> {

    boolean existsBySourceRef(String sourceRef);

    List<IhMeasurement> findBySegCodeOrderByMeasuredOnDesc(String segCode);

    List<IhMeasurement> findTop500ByOrderByMeasuredOnDesc();

    Optional<IhMeasurement> findFirstBySegCodeOrderByMeasuredOnDescImportedAtDesc(String segCode);
}
