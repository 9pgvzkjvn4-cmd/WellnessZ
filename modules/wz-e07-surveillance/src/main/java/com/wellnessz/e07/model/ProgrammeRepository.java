package com.wellnessz.e07.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProgrammeRepository extends JpaRepository<Programme, UUID> {

    List<Programme> findByCodeOrderByVersionDesc(String code);

    List<Programme> findByStatusOrderByCodeAsc(String status);

    List<Programme> findAllByOrderByCodeAscVersionDesc();

    Optional<Programme> findFirstByCodeAndStatus(String code, String status);

    Optional<Programme> findByCodeAndVersion(String code, int version);
}
