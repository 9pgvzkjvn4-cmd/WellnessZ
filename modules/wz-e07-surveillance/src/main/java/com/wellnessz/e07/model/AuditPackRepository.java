package com.wellnessz.e07.model;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditPackRepository extends JpaRepository<AuditPack, UUID> {
}
