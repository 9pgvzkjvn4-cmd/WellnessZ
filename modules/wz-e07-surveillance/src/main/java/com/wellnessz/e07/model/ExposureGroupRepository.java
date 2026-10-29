package com.wellnessz.e07.model;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ExposureGroupRepository extends JpaRepository<ExposureGroup, String> {

    List<ExposureGroup> findAllByOrderByCodeAsc();

    List<ExposureGroup> findByActiveTrue();
}
