package com.wellnessz.e07.model;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EnrolmentRepository extends JpaRepository<Enrolment, UUID> {

    List<Enrolment> findByActiveTrue();

    List<Enrolment> findByProgrammeCode(String programmeCode);

    List<Enrolment> findByEmployeeIdAndActiveTrue(String employeeId);

    List<Enrolment> findByEmployeeIdInAndActiveTrue(Collection<String> employeeIds);

    Optional<Enrolment> findByEmployeeIdAndProgrammeCode(String employeeId, String programmeCode);
}
