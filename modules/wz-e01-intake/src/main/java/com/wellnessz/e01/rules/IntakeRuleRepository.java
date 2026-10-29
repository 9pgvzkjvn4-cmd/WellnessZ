package com.wellnessz.e01.rules;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IntakeRuleRepository extends JpaRepository<IntakeRule, Long> {

    List<IntakeRule> findByActiveTrueOrderByPriorityAscIdAsc();

    List<IntakeRule> findAllByOrderByPriorityAscIdAsc();
}
