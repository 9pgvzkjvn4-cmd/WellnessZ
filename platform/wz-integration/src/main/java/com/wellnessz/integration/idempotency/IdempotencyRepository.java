package com.wellnessz.integration.idempotency;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, IdempotencyRecord.Key> {

    @Modifying
    @Query("delete from IdempotencyRecord r where r.createdAt < ?1")
    int purgeOlderThan(Instant cutoff);
}
