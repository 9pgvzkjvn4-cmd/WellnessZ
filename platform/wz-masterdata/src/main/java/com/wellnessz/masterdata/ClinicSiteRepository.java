package com.wellnessz.masterdata;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ClinicSiteRepository extends JpaRepository<ClinicSite, String> {

    List<ClinicSite> findByActiveTrueOrderByCode();

    List<ClinicSite> findByLocationCodeAndActiveTrueOrderByCode(String locationCode);
}
