package com.wellnessz.e14.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ExcursionRepository extends JpaRepository<Excursion, UUID> {

    @Query("select x from Excursion x where (?1 is null or x.status = ?1) order by x.startedAt desc")
    List<Excursion> search(String status);

    /** The excursion still in progress (no end recorded) for a fridge, if any. */
    Optional<Excursion> findFirstByEquipmentIdAndEndedAtIsNullAndStatusNotOrderByStartedAtDesc(UUID equipmentId, String status);

    long countByEquipmentIdAndStatusNot(UUID equipmentId, String status);

    List<Excursion> findByEquipmentIdOrderByStartedAtDesc(UUID equipmentId);
}
