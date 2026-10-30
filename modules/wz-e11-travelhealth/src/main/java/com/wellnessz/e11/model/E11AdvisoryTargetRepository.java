package com.wellnessz.e11.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E11AdvisoryTargetRepository extends JpaRepository<AdvisoryTarget, UUID> {

    List<AdvisoryTarget> findByAdvisoryId(UUID advisoryId);

    List<AdvisoryTarget> findByEmployeeId(String employeeId);

    Optional<AdvisoryTarget> findByAdvisoryIdAndEmployeeId(UUID advisoryId, String employeeId);
}
