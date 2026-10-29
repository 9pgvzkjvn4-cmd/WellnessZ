package com.wellnessz.e03.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AllergyRepository extends JpaRepository<Allergy, UUID> {
    List<Allergy> findByEmployeeIdOrderByRecordedAtDesc(String employeeId);
}
