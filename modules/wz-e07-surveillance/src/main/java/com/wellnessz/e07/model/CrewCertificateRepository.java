package com.wellnessz.e07.model;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CrewCertificateRepository extends JpaRepository<CrewCertificate, UUID> {

    List<CrewCertificate> findBySupersededFalseOrderByValidUntilAsc();

    List<CrewCertificate> findByEmployeeIdAndSupersededFalse(String employeeId);

    List<CrewCertificate> findByEmployeeIdAndCertificateTypeAndSupersededFalse(String employeeId, String certificateType);

    List<CrewCertificate> findByExaminedOnBetweenOrderByExaminedOnAsc(LocalDate from, LocalDate to);
}
