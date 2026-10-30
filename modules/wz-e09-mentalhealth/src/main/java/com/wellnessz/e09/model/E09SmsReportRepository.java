package com.wellnessz.e09.model;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E09SmsReportRepository extends JpaRepository<SmsReport, UUID> {

    Optional<SmsReport> findByQuarter(String quarter);
}
