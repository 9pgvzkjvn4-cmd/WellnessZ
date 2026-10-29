package com.wellnessz.e06.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AbsenceTriggerRuleRepository extends JpaRepository<TriggerRule, UUID> {

    List<TriggerRule> findAllByOrderByCodeAsc();

    List<TriggerRule> findByActiveTrueOrderByCodeAsc();

    Optional<TriggerRule> findByCode(String code);
}
