package com.wellnessz.e12.model;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E12ErgonomicAssessmentRepository extends JpaRepository<ErgonomicAssessment, UUID> {

    List<ErgonomicAssessment> findAllByOrderByAssessedAtDesc();

    List<ErgonomicAssessment> findByUnitCodeInOrderByAssessedAtDesc(Collection<String> unitCodes);
}
