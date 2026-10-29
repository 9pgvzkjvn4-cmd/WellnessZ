package com.wellnessz.ehsintake.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DeadLetterRepository extends JpaRepository<DeadLetter, UUID> {

    List<DeadLetter> findByStatusOrderByReceivedAtDesc(DeadLetter.Status status);

    long countByStatus(DeadLetter.Status status);
}
