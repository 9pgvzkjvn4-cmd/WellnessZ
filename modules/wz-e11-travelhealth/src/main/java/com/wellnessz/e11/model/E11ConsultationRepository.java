package com.wellnessz.e11.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E11ConsultationRepository extends JpaRepository<Consultation, UUID> {

    List<Consultation> findByEmployeeIdOrderByCreatedAtDesc(String employeeId);

    List<Consultation> findTop200ByOrderByCreatedAtDesc();
}
