package com.wellnessz.e33.model;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E33OcrImageRepository extends JpaRepository<OcrImage, UUID> {
}
