package com.wellnessz.e11.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E11CdCaseRepository extends JpaRepository<CdCase, UUID> {

    List<CdCase> findTop500ByOrderByCreatedAtDesc();

    List<CdCase> findByStatusOrderByCreatedAtDesc(String status);
}
