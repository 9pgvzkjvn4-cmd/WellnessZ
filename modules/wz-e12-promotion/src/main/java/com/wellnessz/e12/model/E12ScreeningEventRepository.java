package com.wellnessz.e12.model;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E12ScreeningEventRepository extends JpaRepository<ScreeningEvent, UUID> {

    List<ScreeningEvent> findAllByOrderByEventDateDesc();

    List<ScreeningEvent> findByCampaignIdOrderByEventDateAsc(UUID campaignId);

    List<ScreeningEvent> findByIdIn(Collection<UUID> ids);
}
