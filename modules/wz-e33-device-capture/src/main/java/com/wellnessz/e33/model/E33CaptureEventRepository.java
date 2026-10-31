package com.wellnessz.e33.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E33CaptureEventRepository extends JpaRepository<CaptureEvent, UUID> {

    List<CaptureEvent> findBySessionIdOrderByOccurredAtAsc(UUID sessionId);
}
