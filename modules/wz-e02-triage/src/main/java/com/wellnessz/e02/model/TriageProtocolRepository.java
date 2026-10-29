package com.wellnessz.e02.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TriageProtocolRepository extends JpaRepository<TriageProtocol, Long> {
    List<TriageProtocol> findByCodeOrderByVersionDesc(String code);

    List<TriageProtocol> findAllByOrderByCodeAscVersionDesc();

    boolean existsByCode(String code);
}
