package com.wellnessz.e07.model;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TitreRepository extends JpaRepository<Titre, UUID> {

    List<Titre> findByEmployeeIdOrderByTestedOnDesc(String employeeId);

    List<Titre> findByEmployeeIdInOrderByTestedOnDesc(Collection<String> employeeIds);
}
