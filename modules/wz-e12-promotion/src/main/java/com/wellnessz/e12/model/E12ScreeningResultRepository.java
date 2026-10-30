package com.wellnessz.e12.model;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface E12ScreeningResultRepository extends JpaRepository<ScreeningResult, UUID> {

    List<ScreeningResult> findByEventIdOrderByRecordedAtAsc(UUID eventId);

    List<ScreeningResult> findByEventIdIn(Collection<UUID> eventIds);

    boolean existsByEventIdAndEmployeeId(UUID eventId, String employeeId);

    List<ScreeningResult> findByRecordedAtGreaterThanEqual(Instant since);

    @Query("select r from E12ScreeningResult r where r.outcome = 'ABNORMAL' and r.caseId is null order by r.recordedAt")
    List<ScreeningResult> findAbnormalWithoutFollowUp();
}
