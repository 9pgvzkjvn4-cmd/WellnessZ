package com.wellnessz.masterdata;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PartnerOrganisationRepository extends JpaRepository<PartnerOrganisation, String> {

    List<PartnerOrganisation> findByActiveTrueOrderByName();

    List<PartnerOrganisation> findByOrgTypeInAndActiveTrueOrderByName(List<String> orgTypes);
}
