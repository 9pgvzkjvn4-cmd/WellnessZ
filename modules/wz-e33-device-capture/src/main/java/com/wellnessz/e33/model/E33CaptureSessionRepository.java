package com.wellnessz.e33.model;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface E33CaptureSessionRepository extends JpaRepository<CaptureSession, UUID> {

    List<CaptureSession> findByDeviceInstanceIdAndStateIn(String deviceInstanceId, Collection<String> states);

    List<CaptureSession> findByBatchIdOrderByQueuePositionAsc(UUID batchId);

    List<CaptureSession> findByModeAndStateIn(String mode, Collection<String> states);

    List<CaptureSession> findByStateInAndExpiresAtBefore(Collection<String> states, Instant at);

    List<CaptureSession> findByEmployeeIdOrderByOpenedAtDesc(String employeeId);

    @Query("select s from E33CaptureSession s where s.siteCode = :site and s.openedAt >= :from and s.openedAt < :to")
    List<CaptureSession> findOpenedAtSite(@Param("site") String site, @Param("from") Instant from, @Param("to") Instant to);

    @Query("select s from E33CaptureSession s where s.openedAt >= :from and s.openedAt < :to")
    List<CaptureSession> findOpened(@Param("from") Instant from, @Param("to") Instant to);
}
