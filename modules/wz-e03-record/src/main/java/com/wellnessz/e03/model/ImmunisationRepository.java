package com.wellnessz.e03.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ImmunisationRepository extends JpaRepository<Immunisation, UUID> {
    List<Immunisation> findByEmployeeIdOrderByAdministeredOnDesc(String employeeId);
}
