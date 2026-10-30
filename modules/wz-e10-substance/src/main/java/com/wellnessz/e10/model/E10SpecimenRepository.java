package com.wellnessz.e10.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E10SpecimenRepository extends JpaRepository<Specimen, UUID> {

    Optional<Specimen> findBySpecimenId(String specimenId);

    List<Specimen> findByTestIdOrderByCollectedAtAsc(UUID testId);

    List<Specimen> findByLabCodeAndResultedAtIsNullOrderByCollectedAtAsc(String labCode);
}
