package com.wellnessz.e11.model;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E11ExposureTaskRepository extends JpaRepository<ExposureTask, UUID> {

    List<ExposureTask> findByExposureIdOrderByPositionAsc(UUID exposureId);

    List<ExposureTask> findByExposureIdInOrderByPositionAsc(Collection<UUID> exposureIds);

    Optional<ExposureTask> findByExposureIdAndCode(UUID exposureId, String code);

    List<ExposureTask> findByKindAndStatusAndDueAtBefore(String kind, String status, Instant before);
}
