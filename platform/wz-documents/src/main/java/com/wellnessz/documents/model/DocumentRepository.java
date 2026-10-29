package com.wellnessz.documents.model;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    List<Document> findByEmployeeIdOrderByCreatedAtDesc(String employeeId);

    List<Document> findByCaseIdOrderByCreatedAtDesc(UUID caseId);

    List<Document> findBySeriesIdOrderByVersionAsc(UUID seriesId);

    List<Document> findByIdIn(Collection<UUID> ids);

    @Query("select d from Document d where d.type in :types and d.createdAt < :before and d.legalHold = false and d.status <> 'REVOKED' order by d.createdAt")
    List<Document> findRetentionCandidates(Collection<String> types, Instant before);
}
