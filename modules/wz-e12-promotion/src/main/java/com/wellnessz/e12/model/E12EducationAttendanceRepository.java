package com.wellnessz.e12.model;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E12EducationAttendanceRepository extends JpaRepository<EducationAttendance, UUID> {

    List<EducationAttendance> findAllByOrderBySessionDateDesc();

    List<EducationAttendance> findByProgrammeAndSessionDateGreaterThanEqual(String programme, LocalDate since);

    List<EducationAttendance> findByEmployeeIdOrderBySessionDateDesc(String employeeId);

    boolean existsByEmployeeIdAndProgrammeAndSessionDate(String employeeId, String programme, LocalDate sessionDate);
}
