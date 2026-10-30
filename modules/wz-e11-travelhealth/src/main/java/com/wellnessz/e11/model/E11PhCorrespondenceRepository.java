package com.wellnessz.e11.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E11PhCorrespondenceRepository extends JpaRepository<PhCorrespondence, UUID> {

    List<PhCorrespondence> findByReportIdOrderByRecordedAtAsc(UUID reportId);
}
