package com.wellnessz.e07.model;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface VaccineAdministrationRepository extends JpaRepository<VaccineAdministration, UUID> {

    List<VaccineAdministration> findByEmployeeIdOrderByAdministeredAtAsc(String employeeId);

    List<VaccineAdministration> findByEmployeeIdInOrderByAdministeredAtAsc(Collection<String> employeeIds);

    boolean existsByEmployeeIdAndVaccineCodeAndDoseNumber(String employeeId, String vaccineCode, int doseNumber);
}
