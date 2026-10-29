package com.wellnessz.e03.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LabOrderRepository extends JpaRepository<LabOrder, UUID> {
    List<LabOrder> findByEmployeeIdOrderByOrderedAtDesc(String employeeId);

    List<LabOrder> findByStatusOrderByOrderedAtAsc(String status);

    Optional<LabOrder> findByPlacerOrderNumber(String placerOrderNumber);
}
