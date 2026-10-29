package com.wellnessz.e05.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FoodHandlerEpisodeRepository extends JpaRepository<FoodHandlerEpisode, UUID> {

    List<FoodHandlerEpisode> findByCaseIdOrderByCreatedAtDesc(UUID caseId);

    List<FoodHandlerEpisode> findByDepartmentCodeOrderByUpdatedAtDesc(String departmentCode);
}
