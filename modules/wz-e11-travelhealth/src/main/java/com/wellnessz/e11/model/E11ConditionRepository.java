package com.wellnessz.e11.model;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E11ConditionRepository extends JpaRepository<Condition, String> {

    List<Condition> findAllByOrderByNameAsc();
}
