package com.wellnessz.e03.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface JobEntryRepository extends JpaRepository<JobEntry, UUID> {
    List<JobEntry> findByEmployeeIdOrderByRecordedAtDesc(String employeeId);
}
