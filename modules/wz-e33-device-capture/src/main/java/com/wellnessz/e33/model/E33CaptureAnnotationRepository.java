package com.wellnessz.e33.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E33CaptureAnnotationRepository extends JpaRepository<CaptureAnnotation, UUID> {

    List<CaptureAnnotation> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);
}
