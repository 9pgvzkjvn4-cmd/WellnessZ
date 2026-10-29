package com.wellnessz.e07.model;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CompletionRepository extends JpaRepository<Completion, UUID> {

    List<Completion> findByProgrammeCodeAndPerformedOnBetweenOrderByPerformedOnAsc(String programmeCode, LocalDate from, LocalDate to);

    List<Completion> findByEnrolmentIdOrderByPerformedOnDesc(UUID enrolmentId);
}
