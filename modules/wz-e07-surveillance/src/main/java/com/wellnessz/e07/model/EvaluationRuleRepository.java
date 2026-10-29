package com.wellnessz.e07.model;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EvaluationRuleRepository extends JpaRepository<EvaluationRule, String> {

    List<EvaluationRule> findAllByOrderByCodeAsc();

    List<EvaluationRule> findByActiveTrue();
}
