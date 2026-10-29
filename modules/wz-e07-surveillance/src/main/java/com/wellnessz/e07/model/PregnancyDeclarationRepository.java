package com.wellnessz.e07.model;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PregnancyDeclarationRepository extends JpaRepository<PregnancyDeclaration, UUID> {

    List<PregnancyDeclaration> findByEmployeeIdOrderByDeclaredOnDesc(String employeeId);

    List<PregnancyDeclaration> findByEmployeeIdIn(Collection<String> employeeIds);
}
