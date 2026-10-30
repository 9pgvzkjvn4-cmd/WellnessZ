package com.wellnessz.e10.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E10SelectionRepository extends JpaRepository<Selection, UUID> {

    List<Selection> findAllByOrderByCreatedAtDesc();

    List<Selection> findByPeriodOrderByRoleGroupAscTestTypeAsc(String period);

    Optional<Selection> findByRoleGroupAndPeriodAndTestType(String roleGroup, String period, String testType);
}
