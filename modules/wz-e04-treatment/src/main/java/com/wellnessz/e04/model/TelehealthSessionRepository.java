package com.wellnessz.e04.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TelehealthSessionRepository extends JpaRepository<TelehealthSession, UUID> {

    List<TelehealthSession> findByScheduledAtBetweenOrderByScheduledAt(Instant from, Instant to);

    List<TelehealthSession> findByEmployeeIdOrderByScheduledAtDesc(String employeeId);

    List<TelehealthSession> findByProviderCodeOrderByScheduledAtDesc(String providerCode, Pageable page);

    List<TelehealthSession> findByAppointmentId(UUID appointmentId);
}
