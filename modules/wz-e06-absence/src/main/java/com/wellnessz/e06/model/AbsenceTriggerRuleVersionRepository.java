package com.wellnessz.e06.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AbsenceTriggerRuleVersionRepository extends JpaRepository<TriggerRuleVersion, UUID> {

    List<TriggerRuleVersion> findByRuleIdOrderByVersionAsc(UUID ruleId);
}
