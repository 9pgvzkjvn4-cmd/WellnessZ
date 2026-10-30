package com.wellnessz.e11.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E11CampaignRepository extends JpaRepository<Campaign, UUID> {

    List<Campaign> findAllByOrderByStartDateDesc();
}
