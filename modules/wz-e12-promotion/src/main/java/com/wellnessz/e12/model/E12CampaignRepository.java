package com.wellnessz.e12.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface E12CampaignRepository extends JpaRepository<Campaign, UUID> {

    List<Campaign> findAllByOrderByStartDateDesc();

    List<Campaign> findByStatusOrderByStartDateDesc(String status);

    @Query(value = "select nextval('promotion.ref_seq')", nativeQuery = true)
    long nextRef();
}
