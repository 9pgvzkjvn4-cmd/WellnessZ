package com.wellnessz.documents.model;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RetentionRuleRepository extends JpaRepository<RetentionRule, String> {

    List<RetentionRule> findAllByOrderByRecordClassAsc();
}
