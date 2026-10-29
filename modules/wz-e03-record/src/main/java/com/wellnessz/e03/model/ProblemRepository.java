package com.wellnessz.e03.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProblemRepository extends JpaRepository<Problem, UUID> {
    List<Problem> findByEmployeeIdOrderByRecordedAtDesc(String employeeId);
}
