package com.wellnessz.e14.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DiaryTemplateRepository extends JpaRepository<DiaryTemplate, UUID> {

    List<DiaryTemplate> findBySiteCodeOrderByWeekdayAscStartTimeAsc(String siteCode);

    List<DiaryTemplate> findAllByOrderBySiteCodeAscWeekdayAscStartTimeAsc();
}
