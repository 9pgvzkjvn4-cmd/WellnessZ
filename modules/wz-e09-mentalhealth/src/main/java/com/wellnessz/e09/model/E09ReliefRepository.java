package com.wellnessz.e09.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E09ReliefRepository extends JpaRepository<Relief, UUID> {

    Optional<Relief> findByEmployeeIdAndStatus(String employeeId, String status);

    List<Relief> findByStatusOrderByFromDateDesc(String status);

    List<Relief> findBySupportCaseIdOrderByCreatedAtDesc(UUID supportCaseId);
}
