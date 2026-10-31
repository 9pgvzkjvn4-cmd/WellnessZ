package com.wellnessz.e33.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E33EmployeeSummaryRepository extends JpaRepository<EmployeeSummary, UUID> {

    List<EmployeeSummary> findByEmployeeIdOrderByMeasuredAtDesc(String employeeId);

    Optional<EmployeeSummary> findByObservationId(UUID observationId);
}
