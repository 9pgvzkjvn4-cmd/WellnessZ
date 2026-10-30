package com.wellnessz.e11.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E11ExposureRepository extends JpaRepository<Exposure, UUID> {

    List<Exposure> findTop500ByOrderByOccurredAtDesc();

    boolean existsByIncidentId(UUID incidentId);
}
