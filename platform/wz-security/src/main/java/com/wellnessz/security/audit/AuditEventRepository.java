package com.wellnessz.security.audit;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    Optional<AuditEvent> findTopByOrderByIdDesc();

    List<AuditEvent> findByOccurredAtBetweenOrderByIdAsc(Instant from, Instant to, Pageable pageable);

    @Query("select a from AuditEvent a where a.id > ?1 order by a.id asc")
    List<AuditEvent> findChainAfter(long id, Pageable pageable);
}
