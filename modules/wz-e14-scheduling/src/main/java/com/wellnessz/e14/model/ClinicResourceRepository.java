package com.wellnessz.e14.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ClinicResourceRepository extends JpaRepository<ClinicResource, UUID> {

    List<ClinicResource> findBySiteCodeOrderByTypeAscCodeAsc(String siteCode);

    List<ClinicResource> findBySiteCodeAndTypeAndActiveTrue(String siteCode, String type);

    Optional<ClinicResource> findBySiteCodeAndTypeAndCode(String siteCode, String type, String code);
}
