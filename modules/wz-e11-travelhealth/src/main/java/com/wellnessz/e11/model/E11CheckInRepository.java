package com.wellnessz.e11.model;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E11CheckInRepository extends JpaRepository<CheckIn, UUID> {

    List<CheckIn> findByEmployeeIdAndDueOnBetweenOrderByDueOnDesc(String employeeId, LocalDate from, LocalDate to);

    List<CheckIn> findByDueOnAndStatus(LocalDate dueOn, String status);

    List<CheckIn> findByDueOnBeforeAndStatus(LocalDate dueOn, String status);

    List<CheckIn> findByContactIdOrderByDueOnAsc(UUID contactId);

    boolean existsByContactIdAndDueOn(UUID contactId, LocalDate dueOn);
}
