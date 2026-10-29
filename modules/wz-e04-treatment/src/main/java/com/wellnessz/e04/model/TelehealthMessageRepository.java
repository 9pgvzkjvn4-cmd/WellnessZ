package com.wellnessz.e04.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TelehealthMessageRepository extends JpaRepository<TelehealthMessage, UUID> {

    List<TelehealthMessage> findBySessionIdOrderBySentAtAsc(UUID sessionId);
}
