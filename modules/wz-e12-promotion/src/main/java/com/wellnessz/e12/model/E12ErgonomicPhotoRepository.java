package com.wellnessz.e12.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E12ErgonomicPhotoRepository extends JpaRepository<ErgonomicPhoto, UUID> {

    List<ErgonomicPhoto> findByAssessmentIdOrderByUploadedAtAsc(UUID assessmentId);
}
