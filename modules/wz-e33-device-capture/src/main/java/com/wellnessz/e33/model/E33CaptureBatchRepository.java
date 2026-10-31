package com.wellnessz.e33.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E33CaptureBatchRepository extends JpaRepository<CaptureBatch, UUID> {

    List<CaptureBatch> findByStatus(String status);
}
