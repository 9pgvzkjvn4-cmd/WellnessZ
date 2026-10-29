package com.wellnessz.e07.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CosmicDoseRepository extends JpaRepository<CosmicDose, UUID> {

    Optional<CosmicDose> findByEmployeeIdAndDoseYear(String employeeId, int doseYear);

    List<CosmicDose> findByEmployeeIdOrderByDoseYearDesc(String employeeId);

    List<CosmicDose> findAllByOrderByDoseYearDescEmployeeIdAsc();

    List<CosmicDose> findByDoseYearOrderByEmployeeIdAsc(int doseYear);
}
