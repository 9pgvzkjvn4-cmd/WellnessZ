package com.wellnessz.e04.model;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceDefinitionRepository extends JpaRepository<ServiceDefinition, String> {

    List<ServiceDefinition> findByActiveTrueOrderByCode();
}
