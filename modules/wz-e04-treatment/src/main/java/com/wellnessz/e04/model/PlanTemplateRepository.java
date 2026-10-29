package com.wellnessz.e04.model;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanTemplateRepository extends JpaRepository<PlanTemplate, String> {

    List<PlanTemplate> findByActiveTrueOrderByCode();
}
