package com.wellnessz.e06.model;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AbsenceCertificateRepository extends JpaRepository<Certificate, UUID> {

    List<Certificate> findAllByOrderByReceivedAtDesc();

    List<Certificate> findByEmployeeIdOrderByFromDateDesc(String employeeId);

    List<Certificate> findByEmployeeIdInOrderByFromDateDesc(Collection<String> employeeIds);

    List<Certificate> findBySha256(String sha256);

    List<Certificate> findByCertificateNumber(String certificateNumber);

    List<Certificate> findByEpisodeId(UUID episodeId);

    List<Certificate> findByReceivedAtBetween(Instant from, Instant to);
}
