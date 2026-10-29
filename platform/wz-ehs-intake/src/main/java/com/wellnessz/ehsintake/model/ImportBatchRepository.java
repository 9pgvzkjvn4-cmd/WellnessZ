package com.wellnessz.ehsintake.model;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportBatchRepository extends JpaRepository<ImportBatch, UUID> {
}
