package com.wellnessz.e09.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E09ScreenResultRepository extends JpaRepository<ScreenResult, UUID> {

    List<ScreenResult> findBySupportCaseIdOrderByCreatedAtAsc(UUID supportCaseId);

    List<ScreenResult> findByEmployeeIdOrderByCreatedAtDesc(String employeeId);
}
