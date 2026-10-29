package com.wellnessz.e14.model;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CampaignSlotRepository extends JpaRepository<CampaignSlot, UUID> {

    List<CampaignSlot> findByCampaignIdOrderByStartsAtAscStationAsc(UUID campaignId);

    @Query("select s from CampaignSlot s where s.campaignId = ?1 and s.appointmentId is null and s.startsAt >= ?2 order by s.startsAt, s.station")
    List<CampaignSlot> findFree(UUID campaignId, Instant from);

    Optional<CampaignSlot> findByAppointmentId(UUID appointmentId);
}
