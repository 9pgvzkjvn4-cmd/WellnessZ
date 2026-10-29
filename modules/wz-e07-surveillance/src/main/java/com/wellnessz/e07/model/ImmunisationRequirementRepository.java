package com.wellnessz.e07.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ImmunisationRequirementRepository extends JpaRepository<ImmunisationRequirement, UUID> {

    List<ImmunisationRequirement> findAllByOrderByVaccineCodeAscJobCodeAsc();

    List<ImmunisationRequirement> findByActiveTrue();
}
