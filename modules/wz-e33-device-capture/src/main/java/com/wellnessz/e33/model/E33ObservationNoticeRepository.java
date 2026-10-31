package com.wellnessz.e33.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E33ObservationNoticeRepository extends JpaRepository<ObservationNoticeRow, UUID> {

    List<ObservationNoticeRow> findByFirstAtGreaterThanEqualAndFirstAtLessThan(Instant from, Instant to);
}
