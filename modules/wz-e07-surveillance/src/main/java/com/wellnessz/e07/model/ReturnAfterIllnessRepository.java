package com.wellnessz.e07.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReturnAfterIllnessRepository extends JpaRepository<ReturnAfterIllness, UUID> {

    List<ReturnAfterIllness> findAllByOrderByCreatedAtDesc(Pageable page);

    List<ReturnAfterIllness> findByStatusOrderByCreatedAtDesc(String status, Pageable page);

    boolean existsByEmployeeIdAndStatus(String employeeId, String status);

    List<ReturnAfterIllness> findByEmployeeIdAndStatus(String employeeId, String status);
}
