package com.wellnessz.e11.model;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E11AdvisoryRepository extends JpaRepository<Advisory, UUID> {

    List<Advisory> findAllByOrderByPublishedAtDesc();

    List<Advisory> findByValidUntilGreaterThanEqual(LocalDate day);
}
