package com.wellnessz.e06.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LtaPathwayRepository extends JpaRepository<LtaPathway, UUID> {

    List<LtaPathway> findByStatusOrderByAbsenceStartAsc(String status);

    List<LtaPathway> findAllByOrderByAbsenceStartDesc();

    Optional<LtaPathway> findFirstByEmployeeIdAndStatus(String employeeId, String status);

    List<LtaPathway> findByEmployeeIdOrderByStartedAtDesc(String employeeId);
}
