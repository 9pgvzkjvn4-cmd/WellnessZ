package com.wellnessz.e09.model;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E09PsychosocialActionRepository extends JpaRepository<PsychosocialAction, UUID> {

    List<PsychosocialAction> findByAssessmentIdOrderByDueDateAsc(UUID assessmentId);

    List<PsychosocialAction> findByAssessmentIdIn(Collection<UUID> assessmentIds);
}
